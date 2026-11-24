package io.pelle.sak.grocery

import android.app.Application
import io.pelle.sak.core.SakViewModel
import io.pelle.sak.grocery.api.GroceryApi

// the model of the app for the activity, kept across a rotation (SakViewModel)
class GroceryViewModel(
    application: Application,
) : SakViewModel<GroceryModel>(
        application,
        app = "grocery",
        selfUpdate = BuildConfig.SELF_UPDATE,
        allowUrl = BuildConfig.DEBUG,
        appVersion = BuildConfig.VERSION_NAME,
    ) {
    override val model =
        GroceryModel(
            setup,
            apiOf = { url, token -> GroceryApi(client, url, token, languages) },
        ).started()
}
