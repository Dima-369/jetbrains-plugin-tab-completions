package dima.sweep.localcomplete.service

import dima.sweep.localcomplete.index.LinePrefixMatcher
import dima.sweep.localcomplete.model.IndexedLine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SessionLineCache(
    private val ttlMillis: Long = TimeUnit.MINUTES.toMillis(5),
    private val acceptedTtlMillis: Long = TimeUnit.MINUTES.toMillis(15),
    private val maxEntries: Int = 512,
) {
    private val entries = ConcurrentHashMap<String, Long>()
    private val acceptedEntries = ConcurrentHashMap<String, Long>()

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

        // Check accepted entries first (stronger signal)
        val acceptedTime = acceptedEntries[key]
        if (acceptedTime != null) {
            val age = now - acceptedTime
            if (age <= acceptedTtlMillis) {
                return 1.5 * (1.0 - age.toDouble() / acceptedTtlMillis.toDouble())
            }
            acceptedEntries.remove(key, acceptedTime)
        }

        // Fall back to edit-based scoring
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

    fun rememberAccepted(line: IndexedLine, now: Long = System.currentTimeMillis()) {
        val key = cacheKey(line)
        if (key.isNotEmpty()) {
            acceptedEntries[key] = now
        }
    }

    private fun evictIfOversized() {
        // Evict from acceptedEntries first
        val acceptedOverflow = acceptedEntries.size - maxEntries / 2
        if (acceptedOverflow > 0) {
            acceptedEntries.entries
                .sortedBy { it.value }
                .take(acceptedOverflow)
                .forEach { entry -> acceptedEntries.remove(entry.key, entry.value) }
        }

        // Then evict from regular entries
        val entriesOverflow = entries.size - maxEntries
        if (entriesOverflow <= 0) return

        entries.entries
            .sortedBy { it.value }
            .take(entriesOverflow)
            .forEach { entry -> entries.remove(entry.key, entry.value) }
    }

    private fun cacheKey(line: IndexedLine): String {
        return LinePrefixMatcher.normalizeForLookup(line.originalContent)
    }
}