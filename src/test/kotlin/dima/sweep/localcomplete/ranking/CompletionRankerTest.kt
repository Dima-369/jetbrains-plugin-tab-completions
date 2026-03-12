package dima.sweep.localcomplete.ranking

import dima.sweep.localcomplete.model.CompletionContextKind
import dima.sweep.localcomplete.model.CursorContext
import dima.sweep.localcomplete.model.FileRecord
import dima.sweep.localcomplete.model.IndexedLine
import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionRankerTest {
    @Test
    fun `exact case match is ranked ahead of case-insensitive match`() {
        val upper = candidate("MyValue = compute()", "/tmp/upper.kt", 1)
        val lower = candidate("myValue = compute()", "/tmp/lower.kt", 1)
        val records = recordsFor(upper, lower)

        val ranked = CompletionRanker.rank(
            candidates = sequenceOf(upper, lower),
            cursorContext = context(rawPrefixText = "MyV", normalizedPrefix = "MyV"),
            fileRecords = records,
            fileRecordByPath = { path -> records.firstOrNull { it.absolutePath == path } },
            limit = 2,
        )

        assertEquals("MyValue = compute()", ranked.first().indexedLine.originalContent)
    }

    @Test
    fun `moderately frequent lines outrank generic boilerplate`() {
        val focused = listOf(
            candidate("domainSpecificCall()", "/tmp/a.kt", 1),
            candidate("domainSpecificCall()", "/tmp/b.kt", 1),
            candidate("domainSpecificCall()", "/tmp/c.kt", 1),
        )
        val generic = (1..12).map { index ->
            candidate("return;", "/tmp/generic$index.kt", 1)
        }
        val allCandidates = focused + generic
        val records = recordsFor(*allCandidates.toTypedArray())

        val ranked = CompletionRanker.rank(
            candidates = allCandidates.asSequence(),
            cursorContext = context(rawPrefixText = "", normalizedPrefix = ""),
            fileRecords = records,
            fileRecordByPath = { path -> records.firstOrNull { it.absolutePath == path } },
            limit = 2,
        )

        assertEquals("domainSpecificCall()", ranked.first().indexedLine.originalContent)
    }

    private fun candidate(content: String, path: String, lineNumber: Int): IndexedLine {
        return IndexedLine(content.trim(), content, "", path, lineNumber, listOf(1L))
    }

    private fun recordsFor(vararg candidates: IndexedLine): List<FileRecord> {
        return candidates.map { candidate ->
            FileRecord(candidate.sourceFilePath, "kt", 1L, listOf(candidate), candidate.originalContent.length.toLong())
        }
    }

    private fun context(rawPrefixText: String, normalizedPrefix: String): CursorContext {
        return CursorContext(
            normalizedPrefix = normalizedPrefix,
            leadingWhitespace = "",
            completionContextKind = CompletionContextKind.CODE,
            fileExtension = "kt",
            filePath = "/tmp/current.kt",
            projectBasePath = "/tmp",
            contextHashes = listOf(1L),
            lineNumber = 1,
            rawPrefixText = rawPrefixText,
            rawSuffixText = "",
        )
    }
}