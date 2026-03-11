package dima.sweep.localcomplete.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import dima.sweep.localcomplete.index.LineIndex
import dima.sweep.localcomplete.model.CursorContext
import dima.sweep.localcomplete.model.IndexStats
import dima.sweep.localcomplete.model.RankedCompletion
import dima.sweep.localcomplete.settings.LocalCompleteSettings
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

    init {
        val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
        scheduler.scheduleWithFixedDelay({ flushNow() }, 10, 10, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ evictIfNeeded() }, 10, 10, TimeUnit.SECONDS)
    }

    companion object {
        fun getInstance(): LineIndexService = service()
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
        val sizeBytes = content.toByteArray().size.toLong()

        lock.write {
            lineIndex.removeFile(path)
            if (sizeBytes <= settings.maxFileSizeBytes) {
                lineIndex.indexFile(
                    path = path,
                    content = content,
                    extension = extension,
                    timestamp = System.currentTimeMillis(),
                    sizeBytes = sizeBytes,
                    maxLineLength = settings.skipLongerColumnLines,
                )
            }
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

    private fun evictIfNeeded() {
        val maxRememberedFiles = LocalCompleteSettings.getInstance().maxRememberedFiles
        lock.write {
            val paths = lineIndex.oldestPathsFirst()
            if (paths.size <= maxRememberedFiles) return

            paths.take(paths.size - maxRememberedFiles).forEach { path ->
                lineIndex.removeFile(path)
                dirty.set(true)
            }
        }
    }
}