package io.pelle.sak.notes

import android.app.Application
import android.os.LocaleList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.engine.okhttp.OkHttp
import io.pelle.sak.core.api.sakClient
import io.pelle.sak.core.session.DataStoreSessionStore
import io.pelle.sak.core.update.AndroidUpdater
import io.pelle.sak.notes.api.NotesApi
import io.pelle.sak.notes.ui.MicrophoneRecorder

// the model of the app for the activity, kept across a rotation; one okhttp engine and one client for
// every api
class NotesViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val engine = OkHttp.create()
    private val client = sakClient(engine)

    val model =
        NotesModel(
            store = DataStoreSessionStore(application),
            apiOf = { url, token -> NotesApi(client, url, token, LocaleList.getDefault().toLanguageTags()) },
            scope = viewModelScope,
            allowUrl = BuildConfig.DEBUG,
            appVersion = BuildConfig.VERSION_NAME,
            updater = AndroidUpdater(application, engine, app = "notes"),
            recorder = MicrophoneRecorder(),
        ).also { it.start() }

    override fun onCleared() {
        client.close()
        engine.close()
    }
}
