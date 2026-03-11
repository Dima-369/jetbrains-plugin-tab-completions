package dima.sweep.localcomplete.provider

import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.DefaultInlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import dima.sweep.localcomplete.LocalCompleteKeys
import dima.sweep.localcomplete.index.ContextHash
import dima.sweep.localcomplete.model.CursorContext
import dima.sweep.localcomplete.model.IndexedLine
import dima.sweep.localcomplete.service.LineIndexService
import dima.sweep.localcomplete.settings.LocalCompleteSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class LocalLineCompletionProvider : DebouncedInlineCompletionProvider() {
    private val logger = Logger.getInstance(LocalLineCompletionProvider::class.java)

    override val id = InlineCompletionProviderID("dima.sweep.localcomplete.LocalLineCompletionProvider")

    override val insertHandler: InlineCompletionInsertHandler = object : DefaultInlineCompletionInsertHandler() {
        override fun afterInsertion(environment: InlineCompletionInsertEnvironment, elements: List<InlineCompletionElement>) {
            try {
                super.afterInsertion(environment, elements)

                val settings = LocalCompleteSettings.getInstance()
                val editor = environment.editor
                if (!settings.moveCaretDownOnTabAccept) return
                if (editor.getUserData(LocalCompleteKeys.TAB_ACCEPT_IN_PROGRESS) != true) return

                val offset = editor.caretModel.offset
                val document = editor.document
                val lineNumber = document.getLineNumber(offset)
                if (offset != document.getLineEndOffset(lineNumber)) return

                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater

                    val caret = editor.caretModel.currentCaret
                    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
                    runCatching {
                        EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_ENTER)
                            .execute(editor, caret, dataContext)
                    }.onFailure {
                        logger.warn("Failed to move caret to next line after inline completion insertion", it)
                    }
                }
            } finally {
                environment.editor.putUserData(LocalCompleteKeys.TAB_ACCEPT_IN_PROGRESS, null)
            }
        }
    }

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return LocalCompleteSettings.getInstance().enabled &&
            (event is InlineCompletionEvent.DocumentChange || event is InlineCompletionEvent.DirectCall)
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration {
        return LocalCompleteSettings.getInstance().debounceMs.milliseconds
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val result = readAction {
            val document = request.document
            val fullText = document.text
            val offset = request.endOffset
            val file = FileDocumentManager.getInstance().getFile(document) ?: return@readAction null
            val lineIndex = document.getLineNumber(offset)
            val lineStart = document.getLineStartOffset(lineIndex)
            val lineEnd = document.getLineEndOffset(lineIndex)
            val lineText = fullText.substring(lineStart, lineEnd)
            val caretColumn = offset - lineStart

            val prefixText = lineText.substring(0, caretColumn)
            val suffixText = lineText.substring(caretColumn)
            val normalizedPrefix = prefixText.trimStart()
            val settings = LocalCompleteSettings.getInstance()
            val allowBlankLineCompletion = normalizedPrefix.isEmpty() && lineText.isBlank()
            if (!allowBlankLineCompletion && normalizedPrefix.length < settings.minPrefixLength) return@readAction null

            val allLines = fullText.split('\n').map { it.removeSuffix("\r") }
            val hashes = ContextHash.forLineGraduated(allLines, lineIndex)
            Pair(
                CursorContext(
                normalizedPrefix = normalizedPrefix,
                leadingWhitespace = prefixText.takeWhile { it == ' ' || it == '\t' },
                fileExtension = file.extension.orEmpty(),
                filePath = file.path,
                projectBasePath = request.editor.project?.basePath,
                contextHashes = hashes,
                lineNumber = lineIndex + 1,
                rawPrefixText = prefixText,
                rawSuffixText = suffixText,
                ),
                fullText,
            )
        } ?: return InlineCompletionSuggestion.Empty

        val (snapshot, documentText) = result

        LineIndexService.getInstance().indexFile(snapshot.filePath, documentText, snapshot.fileExtension)

        val completionText = LineIndexService.getInstance()
            .query(snapshot.normalizedPrefix, snapshot)
            .firstNotNullOfOrNull { rankedCompletion ->
                buildCompletionText(rankedCompletion.indexedLine, snapshot)
                    ?.takeIf { it.isNotEmpty() }
            } ?: return InlineCompletionSuggestion.Empty
        if (completionText.isBlank()) return InlineCompletionSuggestion.Empty

        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(completionText))
        }
    }

    private fun buildCompletionText(indexedLine: IndexedLine, context: CursorContext): String? {
        val reindentedLine = context.leadingWhitespace + indexedLine.originalContent.trimStart()
        if (!reindentedLine.startsWith(context.rawPrefixText)) return null

        val remaining = reindentedLine.removePrefix(context.rawPrefixText)
        if (context.rawSuffixText.isEmpty()) {
            return remaining
        }

        val suffixIndex = remaining.indexOf(context.rawSuffixText)
        if (suffixIndex > 0) {
            return remaining.substring(0, suffixIndex)
        }

        return null
    }
}