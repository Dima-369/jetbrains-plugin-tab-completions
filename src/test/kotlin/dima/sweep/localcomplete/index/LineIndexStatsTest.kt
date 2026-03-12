package dima.sweep.localcomplete.index

import org.junit.Assert.assertEquals
import org.junit.Test

class LineIndexStatsTest {
    @Test
    fun `stats use active prefix index size`() {
        val index = LineIndex()
        index.indexFile(
            path = "/tmp/sample.kt",
            content = "val x = 1\nval x=1\nvalue\n",
            extension = "kt",
            timestamp = 1L,
            sizeBytes = 24L,
            maxLineLength = 200,
        )

        val stats = index.getStats()

        assertEquals(1, stats.fileCount)
        assertEquals(3, stats.lineCount)
        assertEquals(2, stats.indexSize)
    }
}