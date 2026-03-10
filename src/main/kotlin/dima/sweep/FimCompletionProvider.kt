package dima.sweep

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class FimCompletionProvider : DebouncedInlineCompletionProvider() {

    override val id = InlineCompletionProviderID("dima.sweep.FimCompletionProvider")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return event is InlineCompletionEvent.DocumentChange ||
                event is InlineCompletionEvent.DirectCall
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration =
        100.milliseconds

    private fun trimOverlapWithSuffix(prediction: String, suffix: String): String {
        val predLines = prediction.lines()
        val suffixLines = suffix.lines()
        if (predLines.size <= 1 || suffixLines.isEmpty()) return prediction

        // Find the longest suffix of prediction lines that matches a prefix of suffix lines
        for (overlapLen in minOf(predLines.size, suffixLines.size) downTo 1) {
            val predTail = predLines.takeLast(overlapLen)
            val suffHead = suffixLines.take(overlapLen)
            if (predTail.zip(suffHead).all { (a, b) -> a.trim() == b.trim() }) {
                val kept = predLines.dropLast(overlapLen)
                FimClient.log("FIM trimmed $overlapLen overlapping lines from suggestion")
                return if (kept.isEmpty()) predLines.first() else kept.joinToString("\n")
            }
        }
        return prediction
    }

    override suspend fun getSuggestionDebounced(
        request: InlineCompletionRequest
    ): InlineCompletionSuggestion {
        val document = request.document
        val offset = request.endOffset

        val (textBefore, textAfter) = readAction {
            val text = document.text
            Pair(text.substring(0, offset), text.substring(offset))
        }

        val maxBefore = 3000
        val maxAfter = 1500

        val prefix = if (textBefore.length > maxBefore) {
            textBefore.takeLast(maxBefore)
        } else {
            textBefore
        }
        val suffix = textAfter.take(maxAfter)

        val predicted = FimClient.complete(
            prefix = prefix,
            suffix = suffix,
            nPredict = FimClient.DEFAULT_N_PREDICT,
        ) ?: return InlineCompletionSuggestion.Empty

        val trimmed = trimOverlapWithSuffix(predicted, suffix)
        val suggestion = trimmed.trimEnd()

        FimClient.log("FIM suggestion='${suggestion.take(200)}'")

        if (suggestion.isBlank()) return InlineCompletionSuggestion.Empty

        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(suggestion))
        }
    }
}
