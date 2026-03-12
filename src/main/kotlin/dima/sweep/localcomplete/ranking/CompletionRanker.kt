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
        sessionScoreWeight: Double = 25.0,
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
                        score(candidate, fileRecord, cursorContext, oldest, newest, frequency, sessionScore(candidate), sessionScoreWeight),
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
        sessionScoreWeight: Double = 25.0,
    ): Double {
        val contextSimilarity = contextSimilarityScore(candidate, cursorContext)
        val suffixContextBonus = when {
            // ONLY compare candidate's SUFFIX with cursor's SUFFIX
            candidate.suffixContextHashes.any { hash -> hash != 0L && cursorContext.suffixContextHashes.contains(hash) } -> 0.3
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
        val bracketPenalty = bracketBalanceWithPrefix(candidate, cursorContext)
        val lengthPenalty = lengthPenalty(candidate.normalizedContent.length)
        val indentationMatch = indentationMatch(candidate, cursorContext)

        return ((30.0 * contextSimilarity) +
            (25.0 * prefixRatio) +
            (15.0 * exactCaseMatch) +
            (10.0 * recency) +
            (10.0 * proximity) +
            (sessionScoreWeight * sessionScore) +
            (5.0 * suffixContextBonus) +
            (5.0 * contentQuality) +
            (5.0 * freqScore) +
            (3.0 * extensionMatch) +
            (8.0 * indentationMatch)) * bracketPenalty * lengthPenalty
    }

    private fun contextSimilarityScore(candidate: IndexedLine, cursorContext: CursorContext): Double {
        if (candidate.contextHash != 0L && candidate.contextHash == cursorContext.contextHash) {
            return 1.0
        }

        var bestDepthScore = 0.0
        for ((depth, hash) in cursorContext.prefixContextHashes.withIndex()) {
            if (hash != 0L && candidate.prefixContextHashes.getOrNull(depth) == hash) {
                val depthScore = 1.0 - (depth * 0.2)
                bestDepthScore = maxOf(bestDepthScore, depthScore)
            }
        }

        return bestDepthScore
    }

    private fun lengthPenalty(lineLength: Int): Double {
        return when {
            lineLength <= 50 -> 1.0
            lineLength <= 80 -> 0.95
            lineLength <= 100 -> 0.85
            lineLength <= 120 -> 0.70
            lineLength <= 150 -> 0.55
            else -> 0.40
        }
    }

    private fun bracketBalanceWithPrefix(candidate: IndexedLine, cursorContext: CursorContext): Double {
        val prefixMatchEnd = LinePrefixMatcher.findMatchEnd(candidate.normalizedContent, cursorContext.rawPrefixText)
        val completionPart = if (prefixMatchEnd != null) {
            candidate.normalizedContent.substring(prefixMatchEnd)
        } else {
            candidate.normalizedContent
        }
        val combinedText = cursorContext.rawPrefixText + completionPart
        return LineFilter.bracketBalancePenalty(combinedText, cursorContext.rawSuffixText)
    }

    private fun exactCaseMatch(candidate: IndexedLine, cursorContext: CursorContext): Double {
        val trimmedPrefix = cursorContext.rawPrefixText.trimStart()
        if (trimmedPrefix.isEmpty()) return 0.0
        return if (candidate.originalContent.trimStart().startsWith(trimmedPrefix)) 1.0 else 0.0
    }

    private fun frequencyScore(frequency: Int): Double = when {
        frequency <= 0 -> 0.0
        frequency <= 3 -> 1.0
        frequency <= 15 -> 0.85
        frequency <= 50 -> 0.6
        else -> 0.3
    }

    private fun contentQuality(content: String): Double {
        if (content.isEmpty()) return 0.0
        val alphanumericCount = content.count { it.isLetterOrDigit() }
        return alphanumericCount.toDouble() / content.length.toDouble()
    }

    private fun indentationMatch(candidate: IndexedLine, cursorContext: CursorContext): Double {
        val cursorDepth = indentDepth(cursorContext.leadingWhitespace)
        val candidateDepth = indentDepth(candidate.leadingWhitespace)
        val diff = abs(cursorDepth - candidateDepth)
        return when {
            diff == 0 -> 1.0
            diff == 1 -> 0.6
            else -> 0.2
        }
    }

    private fun indentDepth(ws: String): Int = ws.sumOf { if (it == '\t') 4 else 1 }

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