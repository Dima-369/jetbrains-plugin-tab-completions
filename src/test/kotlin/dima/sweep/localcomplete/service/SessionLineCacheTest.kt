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

    private fun line(content: String, lineNumber: Int): IndexedLine {
        return IndexedLine(content.trim(), content, "", "/tmp/current.kt", lineNumber, listOf(0L))
    }
}