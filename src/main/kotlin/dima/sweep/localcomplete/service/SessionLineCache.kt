package dima.sweep.localcomplete.service

import dima.sweep.localcomplete.index.LinePrefixMatcher
import dima.sweep.localcomplete.model.IndexedLine
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

class SessionLineCache(
    private val ttlMillis: Long = TimeUnit.MINUTES.toMillis(5),
    private val maxEntries: Int = 512,
) {
    private val entries = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun rememberUpdatedLines(currentLines: List<IndexedLine>, previousLines: List<IndexedLine>, now: Long = System.currentTimeMillis()) {
        evictExpired(now)
        val previousByLineNumber = previousLines.associateBy { it.lineNumber }
        currentLines.forEach { line ->
            if (previousByLineNumber[line.lineNumber]?.originalContent != line.originalContent) {
                remember(line, now)
            }
        }
    }

    @Synchronized
    fun score(candidate: IndexedLine, now: Long = System.currentTimeMillis()): Double {
        evictExpired(now)
        val key = cacheKey(candidate)
        val lastSeen = entries[key] ?: return 0.0
        val freshness = 1.0 - ((now - lastSeen).coerceAtLeast(0L).toDouble() / ttlMillis.toDouble())
        return freshness.coerceIn(0.0, 1.0)
    }

    @Synchronized
    fun remember(line: IndexedLine, now: Long = System.currentTimeMillis()) {
        val key = cacheKey(line)
        if (key.isNotEmpty()) {
            entries[key] = now
        }
    }

    private fun evictExpired(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > ttlMillis) {
                iterator.remove()
            }
        }
    }

    private fun cacheKey(line: IndexedLine): String {
        return LinePrefixMatcher.normalizeForLookup(line.originalContent)
    }
}