package dima.sweep.localcomplete.model

data class RankedCompletion(
    val indexedLine: IndexedLine,
    val score: Double,
)