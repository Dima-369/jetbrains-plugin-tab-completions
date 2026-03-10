package dima.sweep

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class GenericCompletionProvider : DebouncedInlineCompletionProvider() {

    override val id = InlineCompletionProviderID("dima.sweep.GenericCompletionProvider")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return event is InlineCompletionEvent.DocumentChange ||
                event is InlineCompletionEvent.DirectCall
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration =
        100.milliseconds

    override suspend fun getSuggestionDebounced(
        request: InlineCompletionRequest
    ): InlineCompletionSuggestion {
        val document = request.document
        val offset = request.endOffset

        val (textBefore, textAfter, filePath) = readAction {
            val text = document.text
            val file = request.file
            val path = file.virtualFile?.name ?: file.name
            CursorContext(
                text.substring(0, offset),
                text.substring(offset),
                path
            )
        }

        val (systemPrompt, userPrompt) = buildPrompt(filePath, textBefore, textAfter)

        val predicted = LlmClient.chatComplete(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            maxTokens = 256,
        ) ?: return InlineCompletionSuggestion.Empty

        val suggestion = predicted.trimEnd()

        LlmClient.log("Generic suggestion='${suggestion.take(200)}'")

        if (suggestion.isBlank()) return InlineCompletionSuggestion.Empty

        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(suggestion))
        }
    }

    private data class CursorContext(
        val textBefore: String,
        val textAfter: String,
        val filePath: String,
    )

    private data class PromptPair(val system: String, val user: String)

    private fun buildPrompt(
        filePath: String,
        textBefore: String,
        textAfter: String,
    ): PromptPair {
        val maxBefore = 2000
        val maxAfter = 1000

        val before = if (textBefore.length > maxBefore) {
            textBefore.takeLast(maxBefore)
        } else {
            textBefore
        }
        val after = textAfter.take(maxAfter)

        val system = """You are a code completion assistant. You will be given code with a <CURSOR> marker. Output ONLY the code that should be inserted at <CURSOR>. No explanations, no markdown, no repeating surrounding code. Just the raw insertion text."""

        val user = "$before<CURSOR>$after"

        return PromptPair(system, user)
    }
}
