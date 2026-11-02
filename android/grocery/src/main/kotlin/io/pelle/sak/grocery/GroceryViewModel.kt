package io.pelle.sak.grocery

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.pelle.sak.core.SakViewModel
import io.pelle.sak.grocery.api.GroceryApi

// the model of the app for the activity, kept across a rotation (SakViewModel)
class GroceryViewModel(
    application: Application,
) : SakViewModel<GroceryModel>(application, app = "grocery") {
    override val model =
        GroceryModel(
            store = store,
            apiOf = { url, token -> GroceryApi(client, url, token, languages) },
            scope = viewModelScope,
            allowUrl = BuildConfig.DEBUG,
            appVersion = BuildConfig.VERSION_NAME,
            updater = updater,
        ).started()
}
