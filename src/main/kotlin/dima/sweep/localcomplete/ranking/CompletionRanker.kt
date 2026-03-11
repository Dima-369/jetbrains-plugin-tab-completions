package dima.sweep.localcomplete.ranking

import dima.sweep.localcomplete.model.CursorContext
import dima.sweep.localcomplete.model.FileRecord
import dima.sweep.localcomplete.model.IndexedLine
import dima.sweep.localcomplete.model.RankedCompletion
import kotlin.math.max

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

        return candidates
            .mapNotNull { candidate ->
                val fileRecord = fileRecordByPath(candidate.sourceFilePath) ?: return@mapNotNull null
                RankedCompletion(candidate, score(candidate, fileRecord, cursorContext, oldest, newest))
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
    ): Double {
        val contextSimilarity = if (candidate.contextHash == cursorContext.contextHash) 1.0 else 0.0
        val extensionMatch = if (fileRecord.extension == cursorContext.fileExtension) 1.0 else 0.0
        val recency = when {
            newest <= oldest -> 1.0
            else -> (fileRecord.lastIndexedTimestamp - oldest).toDouble() / max(1L, newest - oldest).toDouble()
        }
        val proximity = proximity(candidate.sourceFilePath, cursorContext)
        val prefixRatio = cursorContext.normalizedPrefix.length.toDouble() /
            max(1, candidate.normalizedContent.length).toDouble()

        return (50.0 * contextSimilarity) +
            (20.0 * extensionMatch) +
            (15.0 * recency) +
            (10.0 * proximity) +
            (5.0 * prefixRatio)
    }

    private fun proximity(candidatePath: String, cursorContext: CursorContext): Double {
        if (candidatePath == cursorContext.filePath) return 1.0
        val candidateDir = candidatePath.substringBeforeLast('/', missingDelimiterValue = "")
        val currentDir = cursorContext.filePath.substringBeforeLast('/', missingDelimiterValue = "")
        if (candidateDir.isNotEmpty() && candidateDir == currentDir) return 0.7

        val basePath = cursorContext.projectBasePath ?: return 0.0
        return if (candidatePath.startsWith(basePath)) 0.3 else 0.0
    }
}