package dima.sweep.localcomplete.ranking

import dima.sweep.localcomplete.index.LinePrefixMatcher
import dima.sweep.localcomplete.model.CursorContext
import dima.sweep.localcomplete.model.FileRecord
import dima.sweep.localcomplete.model.IndexedLine
import dima.sweep.localcomplete.model.RankedCompletion
import dima.sweep.localcomplete.index.LineFilter
import kotlin.math.abs
import kotlin.math.max

object CompletionRanker {
    fun rank(
        candidates: Sequence<IndexedLine>,
        cursorContext: CursorContext,
        fileRecords: Collection<FileRecord>,
        fileRecordByPath: (String) -> FileRecord?,
        sessionScore: (IndexedLine) -> Double,
        limit: Int,
    ): List<RankedCompletion> {
        val timestamps = fileRecords.map { it.lastIndexedTimestamp }
        val oldest = timestamps.minOrNull() ?: 0L
        val newest = timestamps.maxOrNull() ?: oldest

        val grouped = LinkedHashMap<String, MutableList<IndexedLine>>()
        for (candidate in candidates) {
            grouped.getOrPut(candidate.normalizedContent) { mutableListOf() }.add(candidate)
        }

        return grouped.values
            .mapNotNull { group ->
                val frequency = group.distinctBy { it.sourceFilePath }.size
                group.mapNotNull candidateLoop@{ candidate ->
                    val fileRecord = fileRecordByPath(candidate.sourceFilePath) ?: return@candidateLoop null
                    RankedCompletion(
                        candidate,
                        score(candidate, fileRecord, cursorContext, oldest, newest, frequency, sessionScore(candidate)),
                    )
                }.maxByOrNull { it.score }
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    private fun score(
        candidate: IndexedLine,
        fileRecord: FileRecord,
        cursorContext: CursorContext,
        oldest: Long,
        newest: Long,
        frequency: Int,
        sessionScore: Double,
    ): Double {
        val contextSimilarity = when {
            candidate.contextHash != 0L && candidate.contextHash == cursorContext.contextHash -> 1.0
            candidate.contextHashes.any { hash -> hash != 0L && cursorContext.prefixContextHashes.contains(hash) } -> 0.5
            else -> 0.0
        }
        val suffixContextBonus = when {
            candidate.contextHashes.any { hash -> hash != 0L && cursorContext.suffixContextHashes.contains(hash) } -> 0.3
            else -> 0.0
        }
        val extensionMatch = if (fileRecord.extension == cursorContext.fileExtension) 1.0 else 0.0
        val recency = when {
            newest <= oldest -> 1.0
            else -> (fileRecord.lastIndexedTimestamp - oldest).toDouble() / max(1L, newest - oldest).toDouble()
        }
        val proximity = proximity(candidate, cursorContext)
        val normalizedPrefixLength = LinePrefixMatcher.normalizeForLookup(cursorContext.normalizedPrefix).length
        val normalizedCandidateLength = max(1, LinePrefixMatcher.normalizeForLookup(candidate.normalizedContent).length)
        val prefixRatio = normalizedPrefixLength.toDouble() / normalizedCandidateLength.toDouble()
        val exactCaseMatch = exactCaseMatch(candidate, cursorContext)
        val freqScore = frequencyScore(frequency)
        val contentQuality = contentQuality(candidate.normalizedContent)
        val bracketPenalty = LineFilter.bracketBalancePenalty(candidate.normalizedContent, cursorContext.rawSuffixText)

        return ((30.0 * contextSimilarity) +
            (25.0 * prefixRatio) +
            (15.0 * exactCaseMatch) +
            (10.0 * recency) +
            (10.0 * proximity) +
            (50.0 * sessionScore) +
            (5.0 * suffixContextBonus) +
            (5.0 * contentQuality) +
            (5.0 * freqScore) +
            (3.0 * extensionMatch)) * bracketPenalty
    }

    private fun exactCaseMatch(candidate: IndexedLine, cursorContext: CursorContext): Double {
        val trimmedPrefix = cursorContext.rawPrefixText.trimStart()
        if (trimmedPrefix.isEmpty()) return 0.0
        return if (candidate.originalContent.trimStart().startsWith(trimmedPrefix)) 1.0 else 0.0
    }

    private fun frequencyScore(frequency: Int): Double {
        return when {
            frequency <= 0 -> 0.0
            frequency == 1 -> 0.9
            frequency <= 5 -> 1.0
            frequency <= 10 -> 0.7
            frequency <= 25 -> 0.4
            else -> 0.15
        }
    }

    private fun contentQuality(content: String): Double {
        if (content.isEmpty()) return 0.0
        val alphanumericCount = content.count { it.isLetterOrDigit() }
        return alphanumericCount.toDouble() / content.length.toDouble()
    }

    private fun proximity(candidate: IndexedLine, cursorContext: CursorContext): Double {
        if (candidate.sourceFilePath == cursorContext.filePath) {
            val distance = abs(candidate.lineNumber - cursorContext.lineNumber)
            return 0.7 + 0.3 / (1.0 + distance / 20.0)
        }

        val candidateDir = candidate.sourceFilePath.substringBeforeLast('/', missingDelimiterValue = "")
        val currentDir = cursorContext.filePath.substringBeforeLast('/', missingDelimiterValue = "")
        if (candidateDir.isNotEmpty() && candidateDir == currentDir) return 0.5

        val basePath = cursorContext.projectBasePath ?: return 0.0
        return if (candidate.sourceFilePath.startsWith(basePath)) 0.2 else 0.0
    }
}