package dima.sweep.localcomplete.service

import dima.sweep.localcomplete.model.IndexedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SessionLineCacheTest {
    @Test
    fun `recently updated lines receive a freshness score`() {
        val cache = SessionLineCache(ttlMillis = TimeUnit.MINUTES.toMillis(5), maxEntries = 8)
        val previous = listOf(line("val count = 1", 1))
        val current = listOf(line("val count = 2", 1))

        cache.rememberUpdatedLines(current, previous, now = 1_000L)

        assertTrue(cache.score(line("val count = 2", 1), now = 1_500L) > 0.9)
        assertEquals(0.0, cache.score(line("val count = 2", 1), now = TimeUnit.MINUTES.toMillis(6)), 0.0)
    }

    @Test
    fun `evicts oldest entries when over capacity`() {
        val cache = SessionLineCache(ttlMillis = TimeUnit.MINUTES.toMillis(5), maxEntries = 2)

        cache.remember(line("val a = 1", 1), now = 100L)
        cache.remember(line("val b = 2", 2), now = 200L)
        cache.rememberUpdatedLines(
            currentLines = listOf(line("val c = 3", 3)),
            previousLines = emptyList(),
            now = 300L,
        )

        assertEquals(0.0, cache.score(line("val a = 1", 1), now = 300L), 0.0)
        assertTrue(cache.score(line("val b = 2", 2), now = 300L) > 0.0)
        assertTrue(cache.score(line("val c = 3", 3), now = 300L) > 0.0)
    }

    private fun line(content: String, lineNumber: Int): IndexedLine {
        return IndexedLine(content.trim(), content, "", "/tmp/current.kt", lineNumber, listOf(0L))
    }
}