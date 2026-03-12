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
    private val prefixContextMap = HashMap<Long, MutableList<IndexedLine>>()
    private val suffixContextMap = HashMap<Long, MutableList<IndexedLine>>()
    private val tokenMap = HashMap<String, MutableList<IndexedLine>>()
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
            for (hash in line.prefixContextHashes) {
                if (hash != 0L) {
                    prefixContextMap.getOrPut(hash) { mutableListOf() }.add(line)
                }
            }
            for (hash in line.suffixContextHashes) {
                if (hash != 0L) {
                    suffixContextMap.getOrPut(hash) { mutableListOf() }.add(line)
                }
            }
            for (token in extractTokens(line.normalizedContent)) {
                tokenMap.getOrPut(token) { mutableListOf() }.add(line)
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

            for (hash in line.prefixContextHashes) {
                if (hash != 0L) {
                    val contextBucket = prefixContextMap[hash]
                    if (contextBucket != null) {
                        contextBucket.removeIf { it.sourceFilePath == path && it.lineNumber == line.lineNumber }
                        if (contextBucket.isEmpty()) prefixContextMap.remove(hash)
                    }
                }
            }
            
            for (hash in line.suffixContextHashes) {
                if (hash != 0L) {
                    val contextBucket = suffixContextMap[hash]
                    if (contextBucket != null) {
                        contextBucket.removeIf { it.sourceFilePath == path && it.lineNumber == line.lineNumber }
                        if (contextBucket.isEmpty()) suffixContextMap.remove(hash)
                    }
                }
            }

            for (token in extractTokens(line.normalizedContent)) {
                val tokenBucket = tokenMap[token]
                if (tokenBucket != null) {
                    tokenBucket.removeIf { it.sourceFilePath == path && it.lineNumber == line.lineNumber }
                    if (tokenBucket.isEmpty()) tokenMap.remove(token)
                }
            }
        }
    }

    fun query(
        prefix: String,
        cursorContext: CursorContext,
        limit: Int,
        sessionScore: (IndexedLine) -> Double = { 0.0 },
        sessionScoreWeight: Double = 25.0,
    ): List<RankedCompletion> {
        val normalizedLookupPrefix = LinePrefixMatcher.normalizeForLookup(prefix)
        val candidates = if (normalizedLookupPrefix.isBlank()) {
            val prefixCandidates = queryByHashes(cursorContext.prefixContextHashes, prefixContextMap)
            val prefixKeys = prefixCandidates
                .map { indexedLineKey(it) }
                .toHashSet()
            val suffixCandidates = queryByHashes(cursorContext.suffixContextHashes, suffixContextMap)
                .filterNot { indexedLineKey(it) in prefixKeys }

            (prefixCandidates + suffixCandidates).asSequence()
        } else {
            val prefixResults = normalizedPrefixMap.subMap(normalizedLookupPrefix, true, normalizedLookupPrefix + '\uffff', true)
                .values
                .asSequence()
                .flatten()
                .toList()

            if (prefixResults.size < TOKEN_FALLBACK_THRESHOLD) {
                val prefixKeys = prefixResults.map { indexedLineKey(it) }.toHashSet()
                val tokenCandidates = queryByLastToken(normalizedLookupPrefix)
                    .filterNot { indexedLineKey(it) in prefixKeys }
                (prefixResults + tokenCandidates).asSequence()
            } else {
                prefixResults.asSequence()
            }
        }
            .filterNot { it.sourceFilePath == cursorContext.filePath && it.lineNumber == cursorContext.lineNumber }
            .filter { cursorContext.completionContextKind.allows(it) }
            .filterNot {
                normalizedLookupPrefix.isBlank() &&
                    cursorContext.nextNonBlankLineNormalized.isNotEmpty() &&
                    LinePrefixMatcher.normalizeForLookup(it.normalizedContent) == cursorContext.nextNonBlankLineNormalized
            }
            .filterNot {
                cursorContext.nearbyNormalizedLines.contains(
                    LinePrefixMatcher.normalizeForLookup(it.normalizedContent)
                )
            }

        return CompletionRanker.rank(
            candidates = candidates,
            cursorContext = cursorContext,
            fileRecords = fileMap.values,
            fileRecordByPath = { fileMap[it] },
            sessionScore = sessionScore,
            limit = limit,
            sessionScoreWeight = sessionScoreWeight,
        )
    }

    private fun queryByHashes(contextHashes: List<Long>, map: HashMap<Long, MutableList<IndexedLine>>): List<IndexedLine> {
        val seenKeys = HashSet<String>()
        return contextHashes
            .asSequence()
            .filter { it != 0L }
            .flatMap { hash -> map[hash].orEmpty().asSequence() }
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

    private fun queryByLastToken(normalizedLookupPrefix: String): List<IndexedLine> {
        val lastToken = extractLastToken(normalizedLookupPrefix) ?: return emptyList()
        return tokenMap[lastToken].orEmpty()
    }

    fun clear() {
        normalizedPrefixMap.clear()
        prefixContextMap.clear()
        suffixContextMap.clear()
        tokenMap.clear()
        fileMap.clear()
    }

    companion object {
        private const val TOKEN_FALLBACK_THRESHOLD = 5
        private const val MIN_TOKEN_LENGTH = 3
        private val WORD_SPLIT_REGEX = Regex("[^a-zA-Z0-9]+")

        fun extractTokens(content: String): Set<String> {
            val words = content.split(WORD_SPLIT_REGEX)
            return words.asSequence()
                .flatMap { splitCamelCase(it) }
                .map { it.lowercase() }
                .filter { it.length >= MIN_TOKEN_LENGTH }
                .toSet()
        }

        private fun splitCamelCase(word: String): Sequence<String> = sequence {
            yield(word) // keep the full word too
            var start = 0
            for (i in 1 until word.length) {
                if (word[i].isUpperCase() && !word[i - 1].isUpperCase()) {
                    if (i - start >= MIN_TOKEN_LENGTH) yield(word.substring(start, i))
                    start = i
                }
            }
            if (word.length - start >= MIN_TOKEN_LENGTH) yield(word.substring(start))
        }

        fun extractLastToken(normalizedPrefix: String): String? {
            val tokens = extractTokens(normalizedPrefix)
            if (tokens.isEmpty()) return null
            // Find the token whose last occurrence in the prefix is latest
            return tokens.maxByOrNull { normalizedPrefix.lowercase().lastIndexOf(it) }
        }
    }
}