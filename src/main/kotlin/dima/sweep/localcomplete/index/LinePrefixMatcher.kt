package dima.sweep.localcomplete.index

import kotlin.math.min

object LinePrefixMatcher {
    fun normalizeForLookup(text: String): String {
        return buildString(text.length) {
            text.trimStart().forEach { character ->
                if (!character.isWhitespace()) {
                    append(character.lowercaseChar())
                }
            }
        }
    }

    fun findMatchEnd(suggestion: String, rawPrefixText: String): Int? {
        var suggestionIndex = 0
        var prefixIndex = 0

        while (prefixIndex < rawPrefixText.length) {
            while (prefixIndex < rawPrefixText.length && rawPrefixText[prefixIndex].isWhitespace()) {
                prefixIndex++
            }
            while (suggestionIndex < suggestion.length && suggestion[suggestionIndex].isWhitespace()) {
                suggestionIndex++
            }

            if (prefixIndex >= rawPrefixText.length) {
                break
            }
            if (suggestionIndex >= suggestion.length) {
                return null
            }
            if (!suggestion[suggestionIndex].equals(rawPrefixText[prefixIndex], ignoreCase = true)) {
                return null
            }

            suggestionIndex++
            prefixIndex++
        }

        return suggestionIndex
    }

    fun removeSuffixOverlap(remaining: String, rawSuffixText: String): String? {
        val trimmedSuffix = rawSuffixText.trimStart()
        if (remaining.isEmpty() || trimmedSuffix.isEmpty()) return remaining

        val embeddedSuffixIndex = remaining.indexOf(trimmedSuffix, ignoreCase = true)
        if (embeddedSuffixIndex >= 0) {
            return remaining.substring(0, embeddedSuffixIndex)
        }

        for (overlapLength in min(remaining.length, trimmedSuffix.length) downTo 1) {
            val overlapStart = remaining.length - overlapLength
            if (remaining.regionMatches(overlapStart, trimmedSuffix, 0, overlapLength, ignoreCase = true)) {
                return remaining.dropLast(overlapLength)
            }
        }

        // FIM logic: suffix is present, but the candidate does not merge with it.
        // Return null so the provider rejects this as a valid inline suggestion.
        return null
    }
}