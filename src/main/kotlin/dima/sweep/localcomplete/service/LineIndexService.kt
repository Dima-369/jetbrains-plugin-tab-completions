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
import dima.sweep.localcomplete.index.ContextHash
import dima.sweep.localcomplete.index.LineFilter
import com.intellij.util.concurrency.AppExecutorUtil
import dima.sweep.localcomplete.index.LineIndex
import dima.sweep.localcomplete.model.CursorContext
import dima.sweep.localcomplete.model.FileRecord
import dima.sweep.localcomplete.model.IndexStats
import dima.sweep.localcomplete.model.IndexedLine
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

    fun indexFile(path: String, content: String, extension: String) {
        ensureLoadedInBackground()
        val settings = LocalCompleteSettings.getInstance()
        val sizeBytes = content.length.toLong()

        if (sizeBytes > settings.maxFileSizeBytes) {
            lock.write {
                lineIndex.removeFile(path)
                dirty.set(true)
            }
            return
        }

        val fileRecord = buildFileRecord(
            path = path,
            content = content,
            extension = extension,
            sizeBytes = sizeBytes,
            maxLineLength = settings.skipLongerColumnLines,
        )

        lock.write {
            lineIndex.removeFile(path)
            lineIndex.loadFileRecord(fileRecord)
            dirty.set(true)
        }
    }

    fun query(prefix: String, cursorContext: CursorContext, limit: Int = 5): List<RankedCompletion> {
        ensureLoadedInBackground()
        if (loading.get() || !LocalCompleteSettings.getInstance().enabled) return emptyList()
        return lock.read { lineIndex.query(prefix, cursorContext, limit) }
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

        val fileData = ApplicationManager.getApplication().runReadAction<List<Triple<String, String, String>>> {
            paths.mapNotNull { path ->
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(path) ?: return@mapNotNull null
                if (virtualFile.fileType.isBinary) return@mapNotNull null
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@mapNotNull null
                Triple(path, document.text, virtualFile.extension.orEmpty())
            }
        }

        fileData.forEach { (path, text, extension) ->
            try {
                indexFile(path, text, extension)
            } catch (t: Throwable) {
                logger.warn("Failed to reindex dirty document: $path", t)
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

    private fun buildFileRecord(
        path: String,
        content: String,
        extension: String,
        sizeBytes: Long,
        maxLineLength: Int,
    ): FileRecord {
        val rawLines = content.split('\n')
        val indexedLines = rawLines.mapIndexedNotNull { index, rawLine ->
            val originalLine = rawLine.removeSuffix("\r")
            val normalizedContent = originalLine.trim()
            if (LineFilter.shouldSkip(normalizedContent, originalLine.length, maxLineLength)) {
                return@mapIndexedNotNull null
            }

            IndexedLine(
                normalizedContent = normalizedContent,
                originalContent = originalLine,
                leadingWhitespace = originalLine.takeWhile { it == ' ' || it == '\t' },
                sourceFilePath = path,
                lineNumber = index + 1,
                contextHashes = ContextHash.forLineGraduated(rawLines, index),
            )
        }

        return FileRecord(
            absolutePath = path,
            extension = extension,
            lastIndexedTimestamp = System.currentTimeMillis(),
            lines = indexedLines,
            sizeBytes = sizeBytes,
        )
    }
}