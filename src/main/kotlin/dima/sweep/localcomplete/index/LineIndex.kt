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
    private val normalizedPrefixMap = TreeMap<String, MutableList<IndexedLine>>()
    private val contextMap = HashMap<Long, MutableList<IndexedLine>>()
    private val fileMap = LinkedHashMap<String, FileRecord>()

    fun indexFile(
        path: String,
        content: String,
        extension: String,
        timestamp: Long,
        sizeBytes: Long,
        maxLineLength: Int,
        activeLineNumbers: Set<Int> = emptySet(),
    ) {
        removeFile(path)
        val fileRecord = FileRecordBuilder.build(
            path = path,
            content = content,
            extension = extension,
            timestamp = timestamp,
            sizeBytes = sizeBytes,
            maxLineLength = maxLineLength,
            activeLineNumbers = activeLineNumbers,
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
            normalizedPrefixMap.getOrPut(LinePrefixMatcher.normalizeForLookup(line.normalizedContent)) { mutableListOf() }
                .add(line)
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
            val normalizedPrefix = LinePrefixMatcher.normalizeForLookup(line.normalizedContent)
            val normalizedBucket = normalizedPrefixMap[normalizedPrefix]
            if (normalizedBucket != null) {
                normalizedBucket.removeIf { it.sourceFilePath == path && it.lineNumber == line.lineNumber }
                if (normalizedBucket.isEmpty()) {
                    normalizedPrefixMap.remove(normalizedPrefix)
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

    fun query(
        prefix: String,
        cursorContext: CursorContext,
        limit: Int,
        sessionScore: (IndexedLine) -> Double = { 0.0 },
    ): List<RankedCompletion> {
        val normalizedLookupPrefix = LinePrefixMatcher.normalizeForLookup(prefix)
        val candidates = if (normalizedLookupPrefix.isBlank()) {
            val prefixCandidates = queryByContextHashes(cursorContext.prefixContextHashes)
            val prefixKeys = prefixCandidates
                .map { indexedLineKey(it) }
                .toHashSet()
            val suffixCandidates = queryByContextHashes(cursorContext.suffixContextHashes)
                .filterNot { indexedLineKey(it) in prefixKeys }

            (prefixCandidates + suffixCandidates).asSequence()
        } else {
            normalizedPrefixMap.subMap(normalizedLookupPrefix, true, normalizedLookupPrefix + '\uffff', true)
                .values
                .asSequence()
                .flatten()
        }
            .filterNot { it.sourceFilePath == cursorContext.filePath && it.lineNumber == cursorContext.lineNumber }
            .filter { cursorContext.completionContextKind.allows(it) }
            .filterNot {
                normalizedLookupPrefix.isBlank() &&
                    cursorContext.nextNonBlankLineNormalized.isNotEmpty() &&
                    LinePrefixMatcher.normalizeForLookup(it.normalizedContent) == cursorContext.nextNonBlankLineNormalized
            }

        return CompletionRanker.rank(
            candidates = candidates,
            cursorContext = cursorContext,
            fileRecords = fileMap.values,
            fileRecordByPath = { fileMap[it] },
            sessionScore = sessionScore,
            limit = limit,
        )
    }

    private fun queryByContextHashes(contextHashes: List<Long>): List<IndexedLine> {
        val seenKeys = HashSet<String>()
        return contextHashes
            .asSequence()
            .filter { it != 0L }
            .flatMap { hash -> contextMap[hash].orEmpty().asSequence() }
            .filter { seenKeys.add(indexedLineKey(it)) }
            .toList()
    }

    private fun indexedLineKey(line: IndexedLine): String = "${line.sourceFilePath}:${line.lineNumber}"

    fun findFileRecord(path: String): FileRecord? = fileMap[path]

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
            indexSize = normalizedPrefixMap.size,
        )
    }

    fun clear() {
        normalizedPrefixMap.clear()
        contextMap.clear()
        fileMap.clear()
    }
}