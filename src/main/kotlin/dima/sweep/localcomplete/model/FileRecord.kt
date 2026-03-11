package dima.sweep.localcomplete.model

data class FileRecord(
    val absolutePath: String,
    val extension: String,
    val lastIndexedTimestamp: Long,
    val lines: List<IndexedLine>,
    val sizeBytes: Long,
)