package dima.sweep.localcomplete.service

import dima.sweep.localcomplete.index.LinePrefixMatcher
import dima.sweep.localcomplete.model.IndexedLine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SessionLineCache(
    private val ttlMillis: Long = TimeUnit.MINUTES.toMillis(5),
    private val maxEntries: Int = 512,
) {
    private val entries = ConcurrentHashMap<String, Long>()

    fun rememberUpdatedLines(currentLines: List<IndexedLine>, previousLines: List<IndexedLine>, now: Long = System.currentTimeMillis()) {
        val previousByLineNumber = previousLines.associateBy { it.lineNumber }
        currentLines.forEach { line ->
            if (previousByLineNumber[line.lineNumber]?.originalContent != line.originalContent) {
                remember(line, now)
            }
        }
        evictIfOversized()
    }

    fun score(candidate: IndexedLine, now: Long = System.currentTimeMillis()): Double {
        val key = cacheKey(candidate)
        val lastSeen = entries[key] ?: return 0.0
        val age = now - lastSeen
        if (age > ttlMillis) {
            entries.remove(key, lastSeen)
            return 0.0
        }
        val freshness = 1.0 - (age.coerceAtLeast(0L).toDouble() / ttlMillis.toDouble())
        return freshness.coerceIn(0.0, 1.0)
    }

    fun remember(line: IndexedLine, now: Long = System.currentTimeMillis()) {
        val key = cacheKey(line)
        if (key.isNotEmpty()) {
            entries[key] = now
        }
    }

    private fun evictIfOversized() {
        val overflow = entries.size - maxEntries
        if (overflow <= 0) return

        entries.entries
            .sortedBy { it.value }
            .take(overflow)
            .forEach { entry -> entries.remove(entry.key, entry.value) }
    }

    private fun cacheKey(line: IndexedLine): String {
        return LinePrefixMatcher.normalizeForLookup(line.originalContent)
    }
}