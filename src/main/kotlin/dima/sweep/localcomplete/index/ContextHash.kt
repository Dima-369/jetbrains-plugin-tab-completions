package dima.sweep.localcomplete.index

object ContextHash {
    fun forLine(lines: List<String>, targetLineIndex: Int): Long {
        val collected = ArrayList<String>(3)
        var index = targetLineIndex - 1
        while (index >= 0 && collected.size < 3) {
            val normalized = normalizeContextLine(lines[index])
            if (normalized.isNotEmpty()) {
                collected += normalized
            }
            index--
        }
        if (collected.isEmpty()) return 0L
        return hash(collected.asReversed())
    }

    private fun normalizeContextLine(line: String): String {
        return line.trim().lowercase()
    }

    private fun hash(lines: List<String>): Long {
        var result = 1125899906842597L
        for (line in lines) {
            for (char in line) {
                result = result * 131 + char.code
            }
            result = result * 131
        }
        return result
    }
}