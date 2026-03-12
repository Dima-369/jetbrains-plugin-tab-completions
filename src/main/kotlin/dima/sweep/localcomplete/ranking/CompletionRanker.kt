package dima.sweep.localcomplete.ranking

import dima.sweep.localcomplete.model.CursorContext
import dima.sweep.localcomplete.model.FileRecord
import dima.sweep.localcomplete.model.IndexedLine
import dima.sweep.localcomplete.model.RankedCompletion
import dima.sweep.localcomplete.index.LineFilter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object CompletionRanker {
    fun rank(
        candidates: Sequence<IndexedLine>,
        cursorContext: CursorContext,
        fileRecords: Collection<FileRecord>,
        fileRecordByPath: (String) -> FileRecord?,
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
                        score(candidate, fileRecord, cursorContext, oldest, newest, frequency),
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
    ): Double {
        val contextSimilarity = when {
            candidate.contextHash != 0L && candidate.contextHash == cursorContext.contextHash -> 1.0
            candidate.contextHashes.any { hash -> hash != 0L && cursorContext.contextHashes.contains(hash) } -> 0.5
            else -> 0.0
        }
        val extensionMatch = if (fileRecord.extension == cursorContext.fileExtension) 1.0 else 0.0
        val recency = when {
            newest <= oldest -> 1.0
            else -> (fileRecord.lastIndexedTimestamp - oldest).toDouble() / max(1L, newest - oldest).toDouble()
        }
        val proximity = proximity(candidate, cursorContext)
        val prefixRatio = cursorContext.normalizedPrefix.length.toDouble() /
            max(1, candidate.normalizedContent.length).toDouble()
        val freqScore = 1.0 - (1.0 / frequency)
        val contentQuality = contentQuality(candidate.normalizedContent)
        val lengthValue = min(candidate.normalizedContent.length, 80).toDouble() / 80.0
        val bracketPenalty = LineFilter.bracketBalancePenalty(candidate.normalizedContent, cursorContext.rawSuffixText)

        return ((40.0 * contextSimilarity) +
            (15.0 * extensionMatch) +
            (15.0 * freqScore) +
            (10.0 * recency) +
            (8.0 * proximity) +
            (5.0 * contentQuality) +
            (4.0 * lengthValue) +
            (3.0 * prefixRatio)) * bracketPenalty
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