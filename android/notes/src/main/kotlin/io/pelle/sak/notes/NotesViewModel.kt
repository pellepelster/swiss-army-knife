package io.pelle.sak.notes

import android.app.Application
import io.pelle.sak.core.SakViewModel
import io.pelle.sak.notes.api.NotesApi
import io.pelle.sak.notes.ui.MicrophoneRecorder

class NotesViewModel(
    application: Application,
) : SakViewModel<NotesModel>(
        application,
        app = "notes",
        selfUpdate = BuildConfig.SELF_UPDATE,
        allowUrl = BuildConfig.DEBUG,
        appVersion = BuildConfig.VERSION_NAME,
    ) {
    override val model =
        NotesModel(
            setup,
            apiOf = { url, token -> NotesApi(client, url, token, languages) },
            recorder = MicrophoneRecorder(),
        ).started()
}
