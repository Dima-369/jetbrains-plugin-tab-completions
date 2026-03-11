package dima.sweep.localcomplete.model

data class IndexedLine(
    val normalizedContent: String,
    val originalContent: String,
    val leadingWhitespace: String,
    val sourceFilePath: String,
    val lineNumber: Int,
    val contextHash: Long,
)