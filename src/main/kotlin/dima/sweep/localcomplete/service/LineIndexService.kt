package dima.sweep.localcomplete.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import dima.sweep.localcomplete.index.FileRecordBuilder
import com.intellij.util.concurrency.AppExecutorUtil
import dima.sweep.localcomplete.index.LineIndex
import dima.sweep.localcomplete.model.CursorContext
import dima.sweep.localcomplete.model.IndexStats
import dima.sweep.localcomplete.model.RankedCompletion
import dima.sweep.localcomplete.settings.LocalCompleteSettings
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

@Service(Service.Level.APP)
class LineIndexService {
    private val logger = Logger.getInstance(LineIndexService::class.java)
    private val lock = ReentrantReadWriteLock()
    private val lineIndex = LineIndex()
    private val dirty = AtomicBoolean(false)
    private val loadStarted = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    private val dirtyDocumentPaths = ConcurrentHashMap.newKeySet<String>()
    private val documentListenerRegistered = AtomicBoolean(false)
    private val sessionLineCache = SessionLineCache()

    private data class DirtyFileData(
        val path: String,
        val text: String,
        val extension: String,
        val activeLineNumbers: Set<Int>,
    )

    init {
        val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
        scheduler.scheduleWithFixedDelay({ flushNow() }, 10, 10, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ evictIfNeeded() }, 10, 10, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ reindexDirtyDocuments() }, 2, 2, TimeUnit.SECONDS)
    }

    companion object {
        fun getInstance(): LineIndexService = service()
    }

    fun registerDocumentListener() {
        if (!documentListenerRegistered.compareAndSet(false, true)) return

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    dirtyDocumentPaths.add(file.path)
                }
            },
            ApplicationManager.getApplication(),
        )
    }

    fun ensureLoadedInBackground() {
        if (!loadStarted.compareAndSet(false, true)) return

        loading.set(true)
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val records = PersistenceManager.load()
                lock.write {
                    val existing = lineIndex.getRecords()
                    if (existing.isEmpty()) {
                        lineIndex.loadRecords(records)
                    } else {
                        val existingPaths = existing.mapTo(mutableSetOf()) { it.absolutePath }
                        val merged = records.filterNot { it.absolutePath in existingPaths } + existing
                        lineIndex.loadRecords(merged)
                    }
                }
            } catch (t: Throwable) {
                logger.warn("Failed to load local line completion index", t)
            } finally {
                loading.set(false)
            }
        }
    }

    fun indexFile(
        path: String,
        content: String,
        extension: String,
        trackSessionChanges: Boolean = false,
        activeLineNumbers: Set<Int> = emptySet(),
    ) {
        ensureLoadedInBackground()
        dirtyDocumentPaths.remove(path)
        val settings = LocalCompleteSettings.getInstance()
        val sizeBytes = content.length.toLong()

        if (sizeBytes > settings.maxFileSizeBytes) {
            lock.write {
                lineIndex.removeFile(path)
                dirty.set(true)
            }
            return
        }

        val fileRecord = FileRecordBuilder.build(
            path = path,
            content = content,
            extension = extension,
            timestamp = System.currentTimeMillis(),
            sizeBytes = sizeBytes,
            maxLineLength = settings.skipLongerColumnLines,
            activeLineNumbers = activeLineNumbers,
        )

        lock.write {
            val previousRecord = lineIndex.findFileRecord(path)
            lineIndex.removeFile(path)
            lineIndex.loadFileRecord(fileRecord)
            if (trackSessionChanges) {
                sessionLineCache.rememberUpdatedLines(fileRecord.lines, previousRecord?.lines.orEmpty())
            }
            dirty.set(true)
        }
    }

    fun query(prefix: String, cursorContext: CursorContext, limit: Int = 5): List<RankedCompletion> {
        ensureLoadedInBackground()
        if (loading.get() || !LocalCompleteSettings.getInstance().enabled) return emptyList()
        return lock.read { lineIndex.query(prefix, cursorContext, limit, sessionLineCache::score) }
    }

    fun removeFile(path: String) {
        ensureLoadedInBackground()
        lock.write {
            lineIndex.removeFile(path)
            dirty.set(true)
        }
    }

    fun getStats(): IndexStats = lock.read { lineIndex.getStats() }

    fun flushNow(force: Boolean = false) {
        val shouldFlush = if (force) true else dirty.compareAndSet(true, false)
        if (!shouldFlush) return

        val records = lock.read { lineIndex.getRecords() }
        try {
            PersistenceManager.save(records)
            if (force) {
                dirty.set(false)
            }
        } catch (t: Throwable) {
            dirty.set(true)
            logger.warn("Failed to persist local line completion index", t)
        }
    }

    private fun reindexDirtyDocuments() {
        if (dirtyDocumentPaths.isEmpty()) return

        val paths = ArrayList(dirtyDocumentPaths)
        paths.forEach(dirtyDocumentPaths::remove)

        val fileData = ApplicationManager.getApplication().runReadAction<List<DirtyFileData>> {
            paths.mapNotNull { path ->
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(path) ?: return@mapNotNull null
                if (virtualFile.fileType.isBinary) return@mapNotNull null
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@mapNotNull null
                val activeLineNumbers = EditorFactory.getInstance().getEditors(document)
                    .flatMap { editor -> editor.caretModel.allCarets }
                    .map { caret -> caret.logicalPosition.line + 1 }
                    .toSet()

                DirtyFileData(
                    path = path,
                    text = document.text,
                    extension = virtualFile.extension.orEmpty(),
                    activeLineNumbers = activeLineNumbers,
                )
            }
        }

        fileData.forEach { data ->
            try {
                indexFile(
                    path = data.path,
                    content = data.text,
                    extension = data.extension,
                    trackSessionChanges = true,
                    activeLineNumbers = data.activeLineNumbers,
                )
            } catch (t: Throwable) {
                logger.warn("Failed to reindex dirty document: ${data.path}", t)
            }
        }
    }

    private fun evictIfNeeded() {
        val maxRememberedFiles = LocalCompleteSettings.getInstance().maxRememberedFiles
        lock.write {
            val stats = lineIndex.getStats()
            if (stats.fileCount <= maxRememberedFiles) return

            lineIndex.oldestFilesFirst(stats.fileCount - maxRememberedFiles).forEach { path ->
                lineIndex.removeFile(path)
                dirty.set(true)
            }
        }
    }

}