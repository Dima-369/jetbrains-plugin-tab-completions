package dima.sweep.localcomplete.index

import dima.sweep.localcomplete.model.CursorContext
import dima.sweep.localcomplete.model.FileRecord
import dima.sweep.localcomplete.model.IndexStats
import dima.sweep.localcomplete.model.IndexedLine
import dima.sweep.localcomplete.model.RankedCompletion
import dima.sweep.localcomplete.ranking.CompletionRanker
import java.util.LinkedHashMap
import java.util.TreeMap

class LineIndex {
    private val lineMap = TreeMap<String, MutableList<IndexedLine>>()
    private val fileMap = LinkedHashMap<String, FileRecord>(16, 0.75f, true)

    fun indexFile(path: String, content: String, extension: String, timestamp: Long, sizeBytes: Long, maxLineLength: Int) {
        removeFile(path)

        val rawLines = content.split('\n')
        val indexedLines = rawLines.mapIndexedNotNull { index, rawLine ->
            val originalLine = rawLine.removeSuffix("\r")
            val normalizedContent = originalLine.trimStart()
            if (LineFilter.shouldSkip(normalizedContent.trim(), originalLine.length, maxLineLength)) {
                return@mapIndexedNotNull null
            }

            IndexedLine(
                normalizedContent = normalizedContent,
                originalContent = originalLine,
                leadingWhitespace = originalLine.takeWhile { it == ' ' || it == '\t' },
                sourceFilePath = path,
                lineNumber = index + 1,
                contextHash = ContextHash.forLine(rawLines, index),
            )
        }

        val fileRecord = FileRecord(
            absolutePath = path,
            extension = extension,
            lastIndexedTimestamp = timestamp,
            lines = indexedLines,
            sizeBytes = sizeBytes,
        )
        fileMap[path] = fileRecord
        indexedLines.forEach { line ->
            lineMap.getOrPut(line.normalizedContent) { mutableListOf() }.add(line)
        }
    }

    fun loadRecords(records: List<FileRecord>) {
        clear()
        for (record in records) {
            fileMap[record.absolutePath] = record
            record.lines.forEach { line ->
                lineMap.getOrPut(line.normalizedContent) { mutableListOf() }.add(line)
            }
        }
    }

    fun removeFile(path: String) {
        val record = fileMap.remove(path) ?: return
        record.lines.forEach { line ->
            val bucket = lineMap[line.normalizedContent] ?: return@forEach
            bucket.removeIf { it.sourceFilePath == path && it.lineNumber == line.lineNumber }
            if (bucket.isEmpty()) {
                lineMap.remove(line.normalizedContent)
            }
        }
    }

    fun query(prefix: String, cursorContext: CursorContext, limit: Int): List<RankedCompletion> {
        if (prefix.isBlank()) return emptyList()
        val candidates = lineMap.subMap(prefix, true, prefix + '\uffff', true)
            .values
            .asSequence()
            .flatten()
            .filterNot { it.sourceFilePath == cursorContext.filePath && it.lineNumber == cursorContext.lineNumber }

        return CompletionRanker.rank(
            candidates = candidates,
            cursorContext = cursorContext,
            fileRecords = fileMap.values,
            fileRecordByPath = { fileMap[it] },
            limit = limit,
        )
    }

    fun getRecords(): List<FileRecord> = fileMap.values.toList()

    fun oldestPathsFirst(): List<String> = fileMap.keys.toList()

    fun getStats(): IndexStats {
        return IndexStats(
            fileCount = fileMap.size,
            lineCount = fileMap.values.sumOf { it.lines.size },
            indexSize = lineMap.size,
        )
    }

    fun clear() {
        lineMap.clear()
        fileMap.clear()
    }
}