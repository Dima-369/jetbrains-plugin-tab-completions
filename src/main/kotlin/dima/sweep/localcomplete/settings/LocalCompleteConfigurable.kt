package dima.sweep.localcomplete.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

class LocalCompleteConfigurable : BoundConfigurable("Local Line Complete") {
    private val settings = LocalCompleteSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox("Enable local line completion")
                .bindSelected(settings::enabled)
        }
        row {
            checkBox("After Tab accept at end of line, move caret to next indented line")
                .bindSelected(settings::moveCaretDownOnTabAccept)
        }
        row("Max remembered files") {
            intTextField(1..10_000)
                .bindIntText(settings::maxRememberedFiles)
        }
        row("Skip lines longer than") {
            intTextField(1..10_000)
                .bindIntText(settings::skipLongerColumnLines)
        }
        row("Max file size bytes") {
            intTextField(1..10_000_000)
                .bindIntText(
                    getter = { settings.maxFileSizeBytes.toInt() },
                    setter = { settings.maxFileSizeBytes = it.toLong() },
                )
        }
        row("Debounce (ms)") {
            intTextField(0..5_000)
                .bindIntText(settings::debounceMs)
        }
        row("Minimum prefix length") {
            intTextField(1..50)
                .bindIntText(settings::minPrefixLength)
        }
        row("Session score weight (0-100, default 25)") {
            intTextField(0..100)
                .comment("Higher values favor recently edited lines. 25 = balanced, 50+ = strongly prefers recent edits")
                .bindIntText(
                    getter = { settings.sessionScoreWeight },
                    setter = { settings.sessionScoreWeight = it },
                )
        }
    }
}