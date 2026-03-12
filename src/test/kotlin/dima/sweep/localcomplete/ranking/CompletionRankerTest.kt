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
            sessionScore = { 0.0 },
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
            sessionScore = { 0.0 },
            limit = 2,
        )

        assertEquals("domainSpecificCall()", ranked.first().indexedLine.originalContent)
    }

    @Test
    fun `singleton specific lines stay ahead of highly repetitive ones`() {
        val singleton = candidate("fetchUserData()", "/tmp/singleton.kt", 1)
        val repetitive = (1..12).map { index ->
            candidate("fetchUserList()", "/tmp/repetitive$index.kt", 1)
        }
        val allCandidates = listOf(singleton) + repetitive
        val records = recordsFor(*allCandidates.toTypedArray())

        val ranked = CompletionRanker.rank(
            candidates = allCandidates.asSequence(),
            cursorContext = context(rawPrefixText = "fetchUser", normalizedPrefix = "fetchUser"),
            fileRecords = records,
            fileRecordByPath = { path -> records.firstOrNull { it.absolutePath == path } },
            sessionScore = { 0.0 },
            limit = 2,
        )

        assertEquals("fetchUserData()", ranked.first().indexedLine.originalContent)
    }

    @Test
    fun `session score can push a recent line to the top`() {
        val recent = candidate("rememberMe()", "/tmp/recent.kt", 1)
        val stale = candidate("rememberYou()", "/tmp/stale.kt", 1)
        val records = recordsFor(recent, stale)

        val ranked = CompletionRanker.rank(
            candidates = sequenceOf(recent, stale),
            cursorContext = context(rawPrefixText = "remember", normalizedPrefix = "remember"),
            fileRecords = records,
            fileRecordByPath = { path -> records.firstOrNull { it.absolutePath == path } },
            sessionScore = { candidate -> if (candidate.originalContent == "rememberMe()") 1.0 else 0.0 },
            limit = 2,
        )

        assertEquals("rememberMe()", ranked.first().indexedLine.originalContent)
    }

    @Test
    fun `suffix context gives a smaller secondary bonus`() {
        val suffixMatched = IndexedLine("alphaBeta()", "alphaBeta()", "", "/tmp/a.kt", 1, listOf(7L, 9L), emptyList())
        val noSuffixMatch = IndexedLine("alphaGamma()", "alphaGamma()", "", "/tmp/b.kt", 1, listOf(7L), emptyList())
        val records = recordsFor(suffixMatched, noSuffixMatch)

        val ranked = CompletionRanker.rank(
            candidates = sequenceOf(suffixMatched, noSuffixMatch),
            cursorContext = context(
                rawPrefixText = "alpha",
                normalizedPrefix = "alpha",
                prefixContextHashes = emptyList(),
                suffixContextHashes = listOf(9L),
            ),
            fileRecords = records,
            fileRecordByPath = { path -> records.firstOrNull { it.absolutePath == path } },
            sessionScore = { 0.0 },
            limit = 2,
        )

        assertEquals("alphaBeta()", ranked.first().indexedLine.originalContent)
    }

    private fun candidate(content: String, path: String, lineNumber: Int): IndexedLine {
        return IndexedLine(content.trim(), content, "", path, lineNumber, listOf(1L), emptyList())
    }

    private fun recordsFor(vararg candidates: IndexedLine): List<FileRecord> {
        return candidates.map { candidate ->
            FileRecord(candidate.sourceFilePath, "kt", 1L, listOf(candidate), candidate.originalContent.length.toLong())
        }
    }

    private fun context(
        rawPrefixText: String,
        normalizedPrefix: String,
        prefixContextHashes: List<Long> = listOf(1L),
        suffixContextHashes: List<Long> = emptyList(),
    ): CursorContext {
        return CursorContext(
            normalizedPrefix = normalizedPrefix,
            leadingWhitespace = "",
            completionContextKind = CompletionContextKind.CODE,
            fileExtension = "kt",
            filePath = "/tmp/current.kt",
            projectBasePath = "/tmp",
            prefixContextHashes = prefixContextHashes,
            suffixContextHashes = suffixContextHashes,
            lineNumber = 1,
            rawPrefixText = rawPrefixText,
            rawSuffixText = "",
        )
    }
}