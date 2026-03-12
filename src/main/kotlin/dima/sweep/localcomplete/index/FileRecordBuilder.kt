package dima.sweep.localcomplete.index

import dima.sweep.localcomplete.model.FileRecord
import dima.sweep.localcomplete.model.IndexedLine

object FileRecordBuilder {
    fun build(
        path: String,
        content: String,
        extension: String,
        timestamp: Long,
        sizeBytes: Long,
        maxLineLength: Int,
        activeLineNumbers: Set<Int> = emptySet(),
    ): FileRecord {
        val rawLines = content.split('\n')
        val indexedLines = rawLines.mapIndexedNotNull { index, rawLine ->
            val currentLineNumber = index + 1
            if (currentLineNumber in activeLineNumbers) return@mapIndexedNotNull null

            val originalLine = rawLine.removeSuffix("\r")
            val normalizedContent = originalLine.trim()
            if (LineFilter.shouldSkip(normalizedContent, originalLine.length, maxLineLength)) {
                return@mapIndexedNotNull null
            }

            IndexedLine(
                normalizedContent = normalizedContent,
                originalContent = originalLine,
                leadingWhitespace = originalLine.takeWhile { it == ' ' || it == '\t' },
                sourceFilePath = path,
                lineNumber = currentLineNumber,
                prefixContextHashes = ContextHash.prefixHashesForLine(rawLines, index),
                suffixContextHashes = ContextHash.suffixHashesForLine(rawLines, index),
            )
        }

        return FileRecord(
            absolutePath = path,
            extension = extension,
            lastIndexedTimestamp = timestamp,
            lines = indexedLines,
            sizeBytes = sizeBytes,
        )
    }
}