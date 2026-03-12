package dima.sweep.localcomplete.index

import dima.sweep.localcomplete.model.CursorContext
import org.junit.Assert.assertEquals
import org.junit.Test

class LineIndexWhitespaceQueryTest {
    @Test
    fun `query matches lines when typed prefix omits spaces`() {
        val index = LineIndex()
        index.indexFile(
            path = "/tmp/sample.kt",
            content = "val x = compute()\n",
            extension = "kt",
            timestamp = 1L,
            sizeBytes = 20L,
            maxLineLength = 200,
        )

        val results = index.query(
            prefix = "val x=",
            cursorContext = CursorContext(
                normalizedPrefix = "val x=",
                leadingWhitespace = "",
                fileExtension = "kt",
                filePath = "/tmp/current.kt",
                projectBasePath = "/tmp",
                contextHashes = listOf(0L),
                lineNumber = 1,
                rawPrefixText = "val x=",
                rawSuffixText = "",
            ),
            limit = 5,
        )

        assertEquals(1, results.size)
        assertEquals("val x = compute()", results.single().indexedLine.originalContent)
    }
}