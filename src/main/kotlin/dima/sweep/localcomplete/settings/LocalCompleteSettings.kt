package dima.sweep.localcomplete.settings

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "LocalCompleteSettings", storages = [Storage("LocalCompleteSettings.xml")])
class LocalCompleteSettings : SerializablePersistentStateComponent<LocalCompleteSettings.SettingsState>(SettingsState()) {
    data class SettingsState(
        val maxRememberedFiles: Int = 500,
        val skipLongerColumnLines: Int = 300,
        val maxFileSizeBytes: Long = 1_000_000,
        val debounceMs: Int = 100,
        val minPrefixLength: Int = 1,
        val enabled: Boolean = true,
    )

    companion object {
        fun getInstance(): LocalCompleteSettings = service()
    }

    var maxRememberedFiles: Int
        get() = state.maxRememberedFiles
        set(value) {
            updateState { it.copy(maxRememberedFiles = value.coerceAtLeast(1)) }
        }

    var skipLongerColumnLines: Int
        get() = state.skipLongerColumnLines
        set(value) {
            updateState { it.copy(skipLongerColumnLines = value.coerceAtLeast(1)) }
        }

    var maxFileSizeBytes: Long
        get() = state.maxFileSizeBytes
        set(value) {
            updateState { it.copy(maxFileSizeBytes = value.coerceAtLeast(1L)) }
        }

    var debounceMs: Int
        get() = state.debounceMs
        set(value) {
            updateState { it.copy(debounceMs = value.coerceAtLeast(0)) }
        }

    var minPrefixLength: Int
        get() = state.minPrefixLength
        set(value) {
            updateState { it.copy(minPrefixLength = value.coerceAtLeast(1)) }
        }

    var enabled: Boolean
        get() = state.enabled
        set(value) {
            updateState { it.copy(enabled = value) }
        }
}