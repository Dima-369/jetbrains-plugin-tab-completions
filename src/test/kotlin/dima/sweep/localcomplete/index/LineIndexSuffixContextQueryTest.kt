package dima.sweep.localcomplete.index

import dima.sweep.localcomplete.model.CompletionContextKind
import dima.sweep.localcomplete.model.CursorContext
import org.junit.Assert.assertEquals
import org.junit.Test

class LineIndexSuffixContextQueryTest {
    @Test
    fun `blank prefix query can use suffix context`() {
        val indexedLines = "logger.info(\"Success\")\nreturn true;\n"
        val currentLines = listOf("", "return true;")
        val index = LineIndex()
        index.indexFile("/tmp/history.kt", indexedLines, "kt", 1L, indexedLines.length.toLong(), 200)

        val results = index.query(
            prefix = "",
            cursorContext = CursorContext(
                normalizedPrefix = "",
                leadingWhitespace = "",
                completionContextKind = CompletionContextKind.CODE,
                fileExtension = "kt",
                filePath = "/tmp/current.kt",
                projectBasePath = "/tmp",
                prefixContextHashes = emptyList(),
                suffixContextHashes = ContextHash.suffixHashesForLine(currentLines, 0),
                lineNumber = 1,
                rawPrefixText = "",
                rawSuffixText = "",
            ),
            limit = 5,
        )

        assertEquals("logger.info(\"Success\")", results.first().indexedLine.originalContent)
    }
}