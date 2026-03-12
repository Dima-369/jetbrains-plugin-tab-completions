package dima.sweep.localcomplete.model

data class CursorContext(
    val normalizedPrefix: String,
    val leadingWhitespace: String,
    val completionContextKind: CompletionContextKind,
    val fileExtension: String,
    val filePath: String,
    val projectBasePath: String?,
    val prefixContextHashes: List<Long>,
    val suffixContextHashes: List<Long>,
    val lineNumber: Int,
    val rawPrefixText: String,
    val rawSuffixText: String,
) {
    val contextHashes: List<Long>
        get() = prefixContextHashes + suffixContextHashes

    val contextHash: Long
        get() = prefixContextHashes.firstOrNull() ?: 0L
}