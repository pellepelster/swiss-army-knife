package io.pelle.sak.core.settings

import io.pelle.sak.api.model.UserSettingResponse
import io.pelle.sak.api.model.UserSettingScopeResponse
import io.pelle.sak.api.model.UserSettingsResponse
import io.pelle.sak.core.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

// the dialog of the settings of the user while it is open
data class UserSettingsDialog(
    // null while they load
    val scopes: List<UserSettingScopeResponse>? = null,
    val error: UiText? = null,
    // a switch is being saved, the others wait
    val saving: Boolean = false,
)

// a setting that does not count now (e.g. no AI set up) only goes back to its default, so a user
// withdraws e.g. the AI also then
val UserSettingResponse.locked get() = !available && value == default

// the settings of the user signed in, for them alone, e.g. the AI of an app (PRIVACY-2), the part of
// SakModel (SakModel.userSettings): a dialog over the screen, a switch saved when it is turned. load and
// save are requests of the session, null when they failed (they said why with failed); changed tells
// the app, e.g. to ask again whether its AI may be used
class UserSettings(
    private val scope: CoroutineScope,
    private val load: suspend (failed: (UiText) -> Unit) -> UserSettingsResponse?,
    private val save: suspend (scope: String, values: Map<String, JsonPrimitive>, failed: (UiText) -> Unit) -> UserSettingScopeResponse?,
    private val changed: () -> Unit,
) {
    private val open = MutableStateFlow<UserSettingsDialog?>(null)

    // null while closed
    val dialog: StateFlow<UserSettingsDialog?> = open.asStateFlow()

    private var loading: Job? = null

    private val failed: (UiText) -> Unit = { text -> open.update { it?.copy(error = text) } }

    fun open() {
        if (open.value != null) return
        open.value = UserSettingsDialog()
        loading =
            scope.launch {
                val answer = load(failed) ?: return@launch
                open.update { it?.copy(scopes = answer.scopes) }
            }
    }

    // saved at once; a switch that could not be saved stays as it was, the dialog says why
    fun set(
        scopeName: String,
        setting: String,
        value: Boolean,
    ) {
        val current = open.value ?: return
        if (current.saving || current.scopes == null) return
        open.value = current.copy(saving = true, error = null)
        scope.launch {
            val answer = save(scopeName, mapOf(setting to JsonPrimitive(value)), failed)
            open.update { dialog ->
                dialog?.copy(saving = false, scopes = answer?.let { saved -> dialog.scopes?.map { if (it.name == saved.name) saved else it } } ?: dialog.scopes)
            }
            if (answer != null) changed()
        }
    }

    // a save on its way still ends, what is loading is not shown
    fun close() {
        loading?.cancel()
        loading = null
        open.value = null
    }
}
