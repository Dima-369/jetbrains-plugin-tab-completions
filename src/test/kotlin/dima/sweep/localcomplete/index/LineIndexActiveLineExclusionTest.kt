package dima.sweep.localcomplete.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LineIndexActiveLineExclusionTest {
    @Test
    fun `active lines are skipped during indexing when requested`() {
        val index = LineIndex()
        index.indexFile(
            path = "/tmp/sample.kt",
            content = "val before = 1\nval partial =\nval after = 2\nval tail = 3\n",
            extension = "kt",
            timestamp = 1L,
            sizeBytes = 54L,
            maxLineLength = 200,
            activeLineNumbers = setOf(2, 4),
        )

        val indexedLines = index.getRecords().single().lines

        assertEquals(listOf(1, 3), indexedLines.map { it.lineNumber })
        assertFalse(indexedLines.any { it.originalContent == "val partial =" })
        assertFalse(indexedLines.any { it.originalContent == "val tail = 3" })
    }
}