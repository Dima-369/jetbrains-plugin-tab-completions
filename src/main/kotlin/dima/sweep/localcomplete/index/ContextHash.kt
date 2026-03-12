package dima.sweep.localcomplete.index

object ContextHash {
    private val numberStringRegex = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\b\\d+\\b")

    fun forLine(lines: List<String>, targetLineIndex: Int): Long {
        val collected = collectContextLines(lines, targetLineIndex, 3)
        if (collected.isEmpty()) return 0L
        return hash(collected.asReversed())
    }

    fun forLineGraduated(lines: List<String>, targetLineIndex: Int): List<Long> {
        val collected = collectContextLines(lines, targetLineIndex, 3)
        if (collected.isEmpty()) return listOf(0L)

        val reversed = collected.asReversed()
        return List(reversed.size) { index ->
            hash(reversed.subList(index, reversed.size))
        }
    }

    private fun collectContextLines(lines: List<String>, targetLineIndex: Int, maxCount: Int): List<String> {
        val collected = ArrayList<String>(maxCount)
        var index = targetLineIndex - 1
        while (index >= 0 && collected.size < maxCount) {
            val normalized = normalizeContextLine(lines[index])
            if (normalized.isNotEmpty()) {
                collected += normalized
            }
            index--
        }
        return collected
    }

    private fun normalizeContextLine(line: String): String {
        return line.replace(numberStringRegex, "#").trim().lowercase()
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