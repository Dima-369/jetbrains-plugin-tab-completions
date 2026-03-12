package dima.sweep.localcomplete.model

data class CursorContext(
    val normalizedPrefix: String,
    val leadingWhitespace: String,
    val completionContextKind: CompletionContextKind,
    val fileExtension: String,
    val filePath: String,
    val projectBasePath: String?,
    val contextHashes: List<Long>,
    val lineNumber: Int,
    val rawPrefixText: String,
    val rawSuffixText: String,
) {
    val contextHash: Long
        get() = contextHashes.firstOrNull() ?: 0L
}