package dima.sweep.localcomplete.model

data class CursorContext(
    val normalizedPrefix: String,
    val leadingWhitespace: String,
    val fileExtension: String,
    val filePath: String,
    val projectBasePath: String?,
    val contextHash: Long,
    val lineNumber: Int,
    val rawPrefixText: String,
    val rawSuffixText: String,
)