package io.pelle.sak.grocery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.engine.okhttp.OkHttp
import io.pelle.sak.grocery.api.GroceryApi
import io.pelle.sak.grocery.session.DataStoreSessionStore

// the model of the app for the activity, kept across a rotation; one okhttp engine for every api
class GroceryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val engine = OkHttp.create()

    val model =
        GroceryModel(
            store = DataStoreSessionStore(application),
            apiOf = { url, token -> GroceryApi(engine, url, token) },
            scope = viewModelScope,
            allowUrl = BuildConfig.DEBUG,
        ).also { it.start() }

    override fun onCleared() {
        engine.close()
    }
}
