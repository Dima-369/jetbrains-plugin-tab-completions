package dima.sweep.localcomplete.index

object ContextHash {
    private const val prefixSalt = 0x13579BDF2468ACEL
    private const val suffixSalt = 0x2468ACE13579BDFL

    fun forLine(lines: List<String>, targetLineIndex: Int): Long {
        return forLineGraduated(lines, targetLineIndex).firstOrNull() ?: 0L
    }

    fun forLineGraduated(lines: List<String>, targetLineIndex: Int): List<Long> {
        val prefixHashes = graduatedHashes(
            collected = collectPrefixContextLines(lines, targetLineIndex, 3).asReversed(),
            salt = prefixSalt,
        )
        val suffixHashes = graduatedHashes(
            collected = collectSuffixContextLines(lines, targetLineIndex, 2),
            salt = suffixSalt,
        )
        return (prefixHashes + suffixHashes).ifEmpty { listOf(0L) }
    }

    private fun collectPrefixContextLines(lines: List<String>, targetLineIndex: Int, maxCount: Int): List<String> {
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

    private fun collectSuffixContextLines(lines: List<String>, targetLineIndex: Int, maxCount: Int): List<String> {
        val collected = ArrayList<String>(maxCount)
        var index = targetLineIndex + 1
        while (index < lines.size && collected.size < maxCount) {
            val normalized = normalizeContextLine(lines[index])
            if (normalized.isNotEmpty()) {
                collected += normalized
            }
            index++
        }
        return collected
    }

    private fun graduatedHashes(collected: List<String>, salt: Long): List<Long> {
        if (collected.isEmpty()) return emptyList()
        return List(collected.size) { index ->
            hash(collected.subList(index, collected.size), salt)
        }
    }

    private fun normalizeContextLine(line: String): String {
        return maskLiteralsAndNumbers(line).trim().lowercase()
    }

    private fun maskLiteralsAndNumbers(line: String): String {
        val masked = StringBuilder(line.length)
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == '"' || character == '\'' -> {
                    masked.append('#')
                    index = skipQuotedLiteral(line, index)
                }
                character.isDigit() -> {
                    val endIndex = skipDigits(line, index)
                    val previous = line.getOrNull(index - 1)
                    val next = line.getOrNull(endIndex)
                    if (!previous.isWordCharacter() && !next.isWordCharacter()) {
                        masked.append('#')
                        index = endIndex
                    } else {
                        masked.append(character)
                        index++
                    }
                }
                else -> {
                    masked.append(character)
                    index++
                }
            }
        }
        return masked.toString()
    }

    private fun skipQuotedLiteral(line: String, startIndex: Int): Int {
        val quote = line[startIndex]
        var index = startIndex + 1
        var escaped = false
        while (index < line.length) {
            val character = line[index]
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == quote) {
                return index + 1
            }
            index++
        }
        return line.length
    }

    private fun skipDigits(line: String, startIndex: Int): Int {
        var index = startIndex
        while (index < line.length && line[index].isDigit()) {
            index++
        }
        return index
    }

    private fun Char?.isWordCharacter(): Boolean {
        return this != null && (isLetterOrDigit() || this == '_')
    }

    private fun hash(lines: List<String>, salt: Long): Long {
        var result = 1125899906842597L xor salt
        for (line in lines) {
            for (char in line) {
                result = result * 131 + char.code
            }
            result = result * 131
        }
        return result
    }
}