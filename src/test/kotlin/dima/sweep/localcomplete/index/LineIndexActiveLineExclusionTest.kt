package dima.sweep.localcomplete.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LineIndexActiveLineExclusionTest {
    @Test
    fun `active line is skipped during indexing when requested`() {
        val index = LineIndex()
        index.indexFile(
            path = "/tmp/sample.kt",
            content = "val before = 1\nval partial =\nval after = 2\n",
            extension = "kt",
            timestamp = 1L,
            sizeBytes = 40L,
            maxLineLength = 200,
            activeLineNumber = 2,
        )

        val indexedLines = index.getRecords().single().lines

        assertEquals(listOf(1, 3), indexedLines.map { it.lineNumber })
        assertFalse(indexedLines.any { it.originalContent == "val partial =" })
    }
}