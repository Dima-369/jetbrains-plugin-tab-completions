package dima.sweep.localcomplete.model

data class IndexedLine(
    val normalizedContent: String,
    val originalContent: String,
    val leadingWhitespace: String,
    val sourceFilePath: String,
    val lineNumber: Int,
    val prefixContextHashes: List<Long>,
    val suffixContextHashes: List<Long>,
) {
    val contextHash: Long
        get() = prefixContextHashes.firstOrNull() ?: 0L
}