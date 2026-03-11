package dima.sweep.localcomplete.index

import dima.sweep.localcomplete.model.CursorContext
import dima.sweep.localcomplete.model.FileRecord
import dima.sweep.localcomplete.model.IndexStats
import dima.sweep.localcomplete.model.IndexedLine
import dima.sweep.localcomplete.model.RankedCompletion
import dima.sweep.localcomplete.ranking.CompletionRanker
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.TreeMap

class LineIndex {
    private val lineMap = TreeMap<String, MutableList<IndexedLine>>()
    private val contextMap = HashMap<Long, MutableList<IndexedLine>>()
    private val fileMap = LinkedHashMap<String, FileRecord>()

    fun indexFile(path: String, content: String, extension: String, timestamp: Long, sizeBytes: Long, maxLineLength: Int) {
        removeFile(path)

        val rawLines = content.split('\n')
        val indexedLines = rawLines.mapIndexedNotNull { index, rawLine ->
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
                lineNumber = index + 1,
                contextHashes = ContextHash.forLineGraduated(rawLines, index),
            )
        }

        val fileRecord = FileRecord(
            absolutePath = path,
            extension = extension,
            lastIndexedTimestamp = timestamp,
            lines = indexedLines,
            sizeBytes = sizeBytes,
        )
        loadFileRecord(fileRecord)
    }

    fun loadRecords(records: List<FileRecord>) {
        clear()
        for (record in records) {
            loadFileRecord(record)
        }
    }

    fun loadFileRecord(record: FileRecord) {
        fileMap[record.absolutePath] = record
        record.lines.forEach { line ->
            lineMap.getOrPut(line.normalizedContent) { mutableListOf() }.add(line)
            for (hash in line.contextHashes) {
                if (hash != 0L) {
                    contextMap.getOrPut(hash) { mutableListOf() }.add(line)
                }
            }
        }
    }

    fun removeFile(path: String) {
        val record = fileMap.remove(path) ?: return
        record.lines.forEach { line ->
            val bucket = lineMap[line.normalizedContent]
            if (bucket != null) {
                bucket.removeIf { it.sourceFilePath == path && it.lineNumber == line.lineNumber }
                if (bucket.isEmpty()) {
                    lineMap.remove(line.normalizedContent)
                }
            }

            for (hash in line.contextHashes) {
                if (hash != 0L) {
                    val contextBucket = contextMap[hash]
                    if (contextBucket != null) {
                        contextBucket.removeIf { it.sourceFilePath == path && it.lineNumber == line.lineNumber }
                        if (contextBucket.isEmpty()) {
                            contextMap.remove(hash)
                        }
                    }
                }
            }
        }
    }

    fun query(prefix: String, cursorContext: CursorContext, limit: Int): List<RankedCompletion> {
        val candidates = if (prefix.isBlank()) {
            cursorContext.contextHashes
                .filter { it != 0L }
                .firstNotNullOfOrNull { hash ->
                    contextMap[hash]?.takeIf { it.isNotEmpty() }
                }?.asSequence() ?: emptySequence()
        } else {
            lineMap.subMap(prefix, true, prefix + '\uffff', true)
                .values
                .asSequence()
                .flatten()
        }.filterNot { it.sourceFilePath == cursorContext.filePath && it.lineNumber == cursorContext.lineNumber }

        return CompletionRanker.rank(
            candidates = candidates,
            cursorContext = cursorContext,
            fileRecords = fileMap.values,
            fileRecordByPath = { fileMap[it] },
            limit = limit,
        )
    }

    fun getRecords(): List<FileRecord> = fileMap.values.toList()

    fun oldestFilesFirst(count: Int): List<String> {
        return fileMap.values
            .sortedBy { it.lastIndexedTimestamp }
            .take(count)
            .map { it.absolutePath }
    }

    fun getStats(): IndexStats {
        return IndexStats(
            fileCount = fileMap.size,
            lineCount = fileMap.values.sumOf { it.lines.size },
            indexSize = lineMap.size,
        )
    }

    fun clear() {
        lineMap.clear()
        contextMap.clear()
        fileMap.clear()
    }
}