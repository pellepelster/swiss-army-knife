package io.pelle.sak.notes

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.pelle.sak.core.SakViewModel
import io.pelle.sak.notes.api.NotesApi
import io.pelle.sak.notes.ui.MicrophoneRecorder

// the model of the app for the activity, kept across a rotation (SakViewModel)
class NotesViewModel(
    application: Application,
) : SakViewModel<NotesModel>(application, app = "notes") {
    override val model =
        NotesModel(
            store = store,
            apiOf = { url, token -> NotesApi(client, url, token, languages) },
            scope = viewModelScope,
            allowUrl = BuildConfig.DEBUG,
            appVersion = BuildConfig.VERSION_NAME,
            updater = updater,
            recorder = MicrophoneRecorder(),
        ).started()
}
