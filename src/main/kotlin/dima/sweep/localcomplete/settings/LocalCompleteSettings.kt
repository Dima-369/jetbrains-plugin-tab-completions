package dima.sweep.localcomplete.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "LocalCompleteSettings", storages = [Storage("LocalCompleteSettings.xml")])
class LocalCompleteSettings : PersistentStateComponent<LocalCompleteSettings.SettingsState> {
    class SettingsState {
        var maxRememberedFiles: Int = 500
        var skipLongerColumnLines: Int = 300
        var maxFileSizeBytes: Long = 1_000_000
        var debounceMs: Int = 100
        var minPrefixLength: Int = 1
        var enabled: Boolean = true
        var moveCaretDownOnTabAccept: Boolean = false
        var sessionScoreWeight: Int = 25
    }

    private var state = SettingsState()

    companion object {
        fun getInstance(): LocalCompleteSettings =
            ApplicationManager.getApplication().getService(LocalCompleteSettings::class.java)
    }

    override fun getState(): SettingsState = state

    override fun loadState(state: SettingsState) {
        this.state = state
    }

    var maxRememberedFiles: Int
        get() = state.maxRememberedFiles
        set(value) {
            state.maxRememberedFiles = value.coerceAtLeast(1)
        }

    var skipLongerColumnLines: Int
        get() = state.skipLongerColumnLines
        set(value) {
            state.skipLongerColumnLines = value.coerceAtLeast(1)
        }

    var maxFileSizeBytes: Long
        get() = state.maxFileSizeBytes
        set(value) {
            state.maxFileSizeBytes = value.coerceAtLeast(1L)
        }

    var debounceMs: Int
        get() = state.debounceMs
        set(value) {
            state.debounceMs = value.coerceAtLeast(0)
        }

    var minPrefixLength: Int
        get() = state.minPrefixLength
        set(value) {
            state.minPrefixLength = value.coerceAtLeast(1)
        }

    var enabled: Boolean
        get() = state.enabled
        set(value) {
            state.enabled = value
        }

    var moveCaretDownOnTabAccept: Boolean
        get() = state.moveCaretDownOnTabAccept
        set(value) {
            state.moveCaretDownOnTabAccept = value
        }

    var sessionScoreWeight: Int
        get() = state.sessionScoreWeight
        set(value) {
            state.sessionScoreWeight = value.coerceIn(0, 100)
        }
}