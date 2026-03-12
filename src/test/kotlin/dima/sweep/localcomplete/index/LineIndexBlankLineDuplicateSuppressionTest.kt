package dima.sweep.localcomplete.index

import dima.sweep.localcomplete.model.CompletionContextKind
import dima.sweep.localcomplete.model.CursorContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineIndexBlankLineDuplicateSuppressionTest {
    @Test
    fun `blank line completion does not suggest the next non blank line again`() {
        val content = "seed()\nsecond()\nthird()\n"
        val index = LineIndex()
        index.indexFile(
            path = "/tmp/sample.kt",
            content = content,
            extension = "kt",
            timestamp = 1L,
            sizeBytes = content.length.toLong(),
            maxLineLength = 200,
        )

        val queryLines = listOf("seed()", "", "second()", "third()")
        val results = index.query(
            prefix = "",
            cursorContext = CursorContext(
                normalizedPrefix = "",
                leadingWhitespace = "",
                completionContextKind = CompletionContextKind.CODE,
                fileExtension = "kt",
                filePath = "/tmp/current.kt",
                projectBasePath = "/tmp",
                prefixContextHashes = ContextHash.prefixHashesForLine(queryLines, 1),
                suffixContextHashes = ContextHash.suffixHashesForLine(queryLines, 1),
                lineNumber = 2,
                rawPrefixText = "",
                rawSuffixText = "",
                nextNonBlankLineNormalized = LinePrefixMatcher.normalizeForLookup("second()"),
            ),
            limit = 5,
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `blank line completion can still suggest next context match when not already below`() {
        val content = "seed()\nsecond()\n"
        val index = LineIndex()
        index.indexFile(
            path = "/tmp/sample.kt",
            content = content,
            extension = "kt",
            timestamp = 1L,
            sizeBytes = content.length.toLong(),
            maxLineLength = 200,
        )

        val queryLines = listOf("seed()", "")
        val results = index.query(
            prefix = "",
            cursorContext = CursorContext(
                normalizedPrefix = "",
                leadingWhitespace = "",
                completionContextKind = CompletionContextKind.CODE,
                fileExtension = "kt",
                filePath = "/tmp/current.kt",
                projectBasePath = "/tmp",
                prefixContextHashes = ContextHash.prefixHashesForLine(queryLines, 1),
                suffixContextHashes = ContextHash.suffixHashesForLine(queryLines, 1),
                lineNumber = 2,
                rawPrefixText = "",
                rawSuffixText = "",
            ),
            limit = 5,
        )

        assertEquals(listOf("second()"), results.map { it.indexedLine.originalContent })
    }

    @Test
    fun `typed prefix completion is not affected by next non blank filtering`() {
        val content = "second()\n"
        val index = LineIndex()
        index.indexFile(
            path = "/tmp/sample.kt",
            content = content,
            extension = "kt",
            timestamp = 1L,
            sizeBytes = content.length.toLong(),
            maxLineLength = 200,
        )

        val results = index.query(
            prefix = "sec",
            cursorContext = CursorContext(
                normalizedPrefix = "sec",
                leadingWhitespace = "",
                completionContextKind = CompletionContextKind.CODE,
                fileExtension = "kt",
                filePath = "/tmp/current.kt",
                projectBasePath = "/tmp",
                prefixContextHashes = listOf(0L),
                suffixContextHashes = listOf(0L),
                lineNumber = 1,
                rawPrefixText = "sec",
                rawSuffixText = "",
                nextNonBlankLineNormalized = LinePrefixMatcher.normalizeForLookup("second()"),
            ),
            limit = 5,
        )

        assertTrue(results.any { it.indexedLine.originalContent == "second()" })
    }
}