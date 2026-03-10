package dima.sweep

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import kotlin.math.max

data class SweepEdit(
    val startOffset: Int,
    val endOffset: Int,
    val replacement: String,
)

private data class SweepEditSession(
    val documentStamp: Long,
    val edits: List<SweepEdit>,
    val selectedIndex: Int,
)

object SweepSuggestedEditSupport {
    private val sessionKey = Key.create<SweepEditSession>("dima.sweep.suggested.edit.session")
    private val highlightersKey = Key.create<List<RangeHighlighter>>("dima.sweep.suggested.edit.highlighters")

    fun update(editor: Editor, edits: List<SweepEdit>) {
        clear(editor)
        if (edits.isEmpty()) return

        val selectedIndex = edits.indices.minByOrNull { index ->
            kotlin.math.abs(edits[index].startOffset - editor.caretModel.offset)
        } ?: 0

        val session = SweepEditSession(editor.document.modificationStamp, edits, selectedIndex)
        editor.putUserData(sessionKey, session)
        render(editor, session)
    }

    fun hasActiveSuggestion(editor: Editor): Boolean = session(editor) != null

    fun shouldApplyCurrentSelection(editor: Editor): Boolean {
        val current = session(editor) ?: return false
        val edit = current.edits.getOrNull(current.selectedIndex) ?: return false
        return isEditFocused(editor, edit)
    }

    fun performSmartAction(editor: Editor) {
        val current = session(editor) ?: return
        val edit = current.edits.getOrNull(current.selectedIndex) ?: return

        if (isEditFocused(editor, edit)) {
            applyAll(editor)
        } else {
            render(editor, current)
            moveCaretToEdit(editor, edit)
        }
    }

    fun jump(editor: Editor, forward: Boolean) {
        val current = session(editor) ?: return
        val nextIndex = if (current.edits.size <= 1) {
            current.selectedIndex
        } else {
            Math.floorMod(current.selectedIndex + if (forward) 1 else -1, current.edits.size)
        }

        val updated = current.copy(selectedIndex = nextIndex)
        editor.putUserData(sessionKey, updated)
        render(editor, updated)
        moveCaretToEdit(editor, updated.edits[nextIndex])
    }

    fun applyAll(editor: Editor) {
        val current = session(editor) ?: return
        val project = editor.project ?: return

        WriteCommandAction.runWriteCommandAction(
            project,
            "Apply Sweep Suggested Edit",
            null,
            Runnable {
                current.edits
                    .sortedByDescending { it.startOffset }
                    .forEach { edit ->
                        editor.document.replaceString(edit.startOffset, edit.endOffset, edit.replacement)
                    }
            }
        )

        val primaryEdit = current.edits.getOrNull(current.selectedIndex) ?: current.edits.first()
        val caretOffset = (primaryEdit.startOffset + primaryEdit.replacement.length)
            .coerceIn(0, editor.document.textLength)
        clear(editor)
        editor.caretModel.moveToOffset(caretOffset)
        editor.selectionModel.removeSelection()
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    fun clear(editor: Editor) {
        editor.getUserData(highlightersKey).orEmpty().forEach { highlighter ->
            editor.markupModel.removeHighlighter(highlighter)
        }
        editor.putUserData(highlightersKey, emptyList())
        editor.putUserData(sessionKey, null)
    }

    private fun session(editor: Editor): SweepEditSession? {
        val session = editor.getUserData(sessionKey) ?: return null
        if (editor.document.modificationStamp == session.documentStamp) {
            return session
        }

        clear(editor)
        return null
    }

    private fun render(editor: Editor, session: SweepEditSession) {
        editor.getUserData(highlightersKey).orEmpty().forEach { highlighter ->
            editor.markupModel.removeHighlighter(highlighter)
        }

        val highlighters = session.edits.mapIndexed { index, edit ->
            val selected = index == session.selectedIndex
            createHighlighter(editor, edit, selected, index, session.edits.size)
        }

        editor.putUserData(highlightersKey, highlighters)
    }

    private fun createHighlighter(
        editor: Editor,
        edit: SweepEdit,
        selected: Boolean,
        index: Int,
        total: Int,
    ): RangeHighlighter {
        val highlighter = if (edit.startOffset < edit.endOffset) {
            editor.markupModel.addRangeHighlighter(
                edit.startOffset,
                edit.endOffset,
                HighlighterLayer.SELECTION - 1,
                attributes(selected),
                HighlighterTargetArea.EXACT_RANGE,
            )
        } else {
            val lineCount = max(editor.document.lineCount, 1)
            val targetOffset = edit.startOffset.coerceIn(0, editor.document.textLength)
            val line = if (editor.document.textLength == 0) 0 else editor.document.getLineNumber(targetOffset)
            editor.markupModel.addLineHighlighter(
                line.coerceIn(0, lineCount - 1),
                HighlighterLayer.SELECTION - 1,
                attributes(selected),
            )
        }

        highlighter.errorStripeMarkColor = if (selected) selectedStripeColor else stripeColor
        highlighter.errorStripeTooltip = "Sweep suggested edit ${index + 1}/$total"
        return highlighter
    }

    private fun moveCaretToEdit(editor: Editor, edit: SweepEdit) {
        val start = edit.startOffset.coerceIn(0, editor.document.textLength)
        val end = edit.endOffset.coerceIn(start, editor.document.textLength)
        editor.caretModel.moveToOffset(start)
        if (end > start) {
            editor.selectionModel.setSelection(start, end)
        } else {
            editor.selectionModel.removeSelection()
        }
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    private fun isEditFocused(editor: Editor, edit: SweepEdit): Boolean {
        val caretOffset = editor.caretModel.offset
        val selectionModel = editor.selectionModel
        val start = edit.startOffset.coerceIn(0, editor.document.textLength)
        val end = edit.endOffset.coerceIn(start, editor.document.textLength)

        return if (end > start) {
            (selectionModel.hasSelection() &&
                selectionModel.selectionStart == start &&
                selectionModel.selectionEnd == end) ||
                caretOffset in start..end
        } else {
            caretOffset == start
        }
    }

    private fun attributes(selected: Boolean): TextAttributes {
        val background = if (selected) selectedBackground else background
        val effect = if (selected) selectedStripeColor else stripeColor
        return TextAttributes(null, background, effect, EffectType.ROUNDED_BOX, Font.PLAIN)
    }

    private val background = JBColor(Color(0xEE, 0xF4, 0xFF), Color(0x26, 0x32, 0x45))
    private val selectedBackground = JBColor(Color(0xDC, 0xEB, 0xFF), Color(0x2E, 0x43, 0x6E))
    private val stripeColor = JBColor(Color(0x80, 0xA7, 0xFF), Color(0x62, 0x82, 0xC0))
    private val selectedStripeColor = JBColor(Color(0x4E, 0x8E, 0xFF), Color(0x8B, 0xC2, 0xFF))
}

class SweepSmartSuggestedEditAction : AnAction(
    "Sweep: Show Suggested Edit",
    "Jump to or apply the detected Sweep edit",
    null,
) {
    override fun update(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        val enabled = editor != null && SweepSuggestedEditSupport.hasActiveSuggestion(editor)
        event.presentation.isEnabled = enabled
        event.presentation.text = if (enabled && editor != null && SweepSuggestedEditSupport.shouldApplyCurrentSelection(editor)) {
            "Sweep: Apply Suggested Edit"
        } else {
            "Sweep: Show Suggested Edit"
        }
        event.presentation.description = if (enabled && editor != null && SweepSuggestedEditSupport.shouldApplyCurrentSelection(editor)) {
            "Apply the detected Sweep edit"
        } else {
            "Jump to the detected Sweep edit"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.getData(CommonDataKeys.EDITOR)?.let { SweepSuggestedEditSupport.performSmartAction(it) }
    }
}