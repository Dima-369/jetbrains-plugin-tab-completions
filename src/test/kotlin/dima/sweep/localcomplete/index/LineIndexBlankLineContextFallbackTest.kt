package dima.sweep.localcomplete.index

import dima.sweep.localcomplete.model.CompletionContextKind
import dima.sweep.localcomplete.model.CursorContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineIndexBlankLineContextFallbackTest {
    @Test
    fun `blank line query can use later context buckets after duplicate next line is filtered`() {
        val index = LineIndex()
        val duplicateFile = "a()\nb()\nnext()\n"
        val usefulFile = "b()\nuseful()\n"

        index.indexFile(
            path = "/tmp/duplicate.kt",
            content = duplicateFile,
            extension = "kt",
            timestamp = 1L,
            sizeBytes = duplicateFile.length.toLong(),
            maxLineLength = 200,
        )
        index.indexFile(
            path = "/tmp/useful.kt",
            content = usefulFile,
            extension = "kt",
            timestamp = 1L,
            sizeBytes = usefulFile.length.toLong(),
            maxLineLength = 200,
        )

        val queryLines = listOf("a()", "b()", "", "next()")
        val results = index.query(
            prefix = "",
            cursorContext = CursorContext(
                normalizedPrefix = "",
                leadingWhitespace = "",
                completionContextKind = CompletionContextKind.CODE,
                fileExtension = "kt",
                filePath = "/tmp/current.kt",
                projectBasePath = "/tmp",
                prefixContextHashes = ContextHash.prefixHashesForLine(queryLines, 2),
                suffixContextHashes = ContextHash.suffixHashesForLine(queryLines, 2),
                lineNumber = 3,
                rawPrefixText = "",
                rawSuffixText = "",
                nextNonBlankLineNormalized = LinePrefixMatcher.normalizeForLookup("next()"),
            ),
            limit = 5,
        )

        assertEquals("useful()", results.first().indexedLine.originalContent)
        assertTrue(results.any { it.indexedLine.originalContent == "useful()" })
        assertFalse(results.any { it.indexedLine.originalContent == "next()" })
    }
}