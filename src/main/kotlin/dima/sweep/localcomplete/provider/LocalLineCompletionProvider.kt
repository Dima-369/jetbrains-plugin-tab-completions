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
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import dima.sweep.localcomplete.LocalCompleteKeys
import dima.sweep.localcomplete.index.ContextHash
import dima.sweep.localcomplete.index.LinePrefixMatcher
import dima.sweep.localcomplete.model.CompletionContextKind
import dima.sweep.localcomplete.model.CursorContext
import dima.sweep.localcomplete.model.IndexedLine
import dima.sweep.localcomplete.service.LineIndexService
import dima.sweep.localcomplete.settings.LocalCompleteSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class LocalLineCompletionProvider : DebouncedInlineCompletionProvider() {
    private val logger = Logger.getInstance(LocalLineCompletionProvider::class.java)

    private data class SuggestionSnapshot(
        val cursorContext: CursorContext,
        val documentText: String,
        val activeLineNumbers: Set<Int>,
    )

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
            val completionContextKind = detectCompletionContext(request, document, offset)
            if (completionContextKind == CompletionContextKind.STRING) return@readAction null
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
            val activeLineNumbers = request.editor.caretModel.allCarets
                .map { caret -> document.getLineNumber(caret.offset) + 1 }
                .toSet()

            val allLines = fullText.split('\n').map { it.removeSuffix("\r") }
            val prefixHashes = ContextHash.prefixHashesForLine(allLines, lineIndex)
            val suffixHashes = ContextHash.suffixHashesForLine(allLines, lineIndex)
            val nextNonBlankLineNormalized = if (allowBlankLineCompletion) {
                findNextNonBlankLineNormalized(allLines, lineIndex)
            } else {
                ""
            }
            SuggestionSnapshot(
                cursorContext = CursorContext(
                normalizedPrefix = normalizedPrefix,
                leadingWhitespace = prefixText.takeWhile { it == ' ' || it == '\t' },
                completionContextKind = completionContextKind,
                fileExtension = file.extension.orEmpty(),
                filePath = file.path,
                projectBasePath = request.editor.project?.basePath,
                prefixContextHashes = prefixHashes,
                suffixContextHashes = suffixHashes,
                lineNumber = lineIndex + 1,
                rawPrefixText = prefixText,
                rawSuffixText = suffixText,
                nextNonBlankLineNormalized = nextNonBlankLineNormalized,
                ),
                documentText = fullText,
                activeLineNumbers = activeLineNumbers,
            )
        } ?: return InlineCompletionSuggestion.Empty

        val snapshot = result
        val cursorContext = snapshot.cursorContext

        LineIndexService.getInstance().indexFile(
            cursorContext.filePath,
            snapshot.documentText,
            cursorContext.fileExtension,
            trackSessionChanges = true,
            activeLineNumbers = snapshot.activeLineNumbers,
        )

        val completionText = LineIndexService.getInstance()
            .query(cursorContext.normalizedPrefix, cursorContext)
            .firstNotNullOfOrNull { rankedCompletion ->
                buildCompletionText(rankedCompletion.indexedLine, cursorContext)
                    ?.takeIf { it.isNotEmpty() }
            } ?: return InlineCompletionSuggestion.Empty
        if (completionText.isBlank()) return InlineCompletionSuggestion.Empty

        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(completionText))
        }
    }

    private fun buildCompletionText(indexedLine: IndexedLine, context: CursorContext): String? {
        val reindentedLine = context.leadingWhitespace + indexedLine.originalContent.trimStart()
        val prefixMatchEnd = LinePrefixMatcher.findMatchEnd(reindentedLine, context.rawPrefixText) ?: return null

        val remaining = reindentedLine.substring(prefixMatchEnd)
        return LinePrefixMatcher.removeSuffixOverlap(remaining, context.rawSuffixText)
    }

    private fun findNextNonBlankLineNormalized(allLines: List<String>, lineIndex: Int): String {
        for (nextIndex in (lineIndex + 1) until allLines.size) {
            val normalized = LinePrefixMatcher.normalizeForLookup(allLines[nextIndex])
            if (normalized.isNotEmpty()) {
                return normalized
            }
        }
        return ""
    }

    private fun detectCompletionContext(
        request: InlineCompletionRequest,
        document: com.intellij.openapi.editor.Document,
        offset: Int,
    ): CompletionContextKind {
        val project = request.editor.project ?: return CompletionContextKind.UNKNOWN
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return CompletionContextKind.UNKNOWN
        val safeOffset = when {
            offset < document.textLength -> offset
            offset > 0 -> offset - 1
            else -> 0
        }
        val elementAtCaret = psiFile.findElementAt(safeOffset) ?: return CompletionContextKind.UNKNOWN
        if (elementAtCaret is PsiComment || PsiTreeUtil.getParentOfType(elementAtCaret, PsiComment::class.java, false) != null) {
            return CompletionContextKind.COMMENT
        }

        val elementType = elementAtCaret.node?.elementType?.toString()?.uppercase().orEmpty()
        return when {
            "COMMENT" in elementType -> CompletionContextKind.COMMENT
            "STRING" in elementType || "CHARACTER" in elementType -> CompletionContextKind.STRING
            else -> CompletionContextKind.CODE
        }
    }
}