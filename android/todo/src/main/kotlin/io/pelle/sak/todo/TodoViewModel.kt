package io.pelle.sak.todo

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.pelle.sak.core.SakViewModel
import io.pelle.sak.todo.api.TodoApi

// the model of the app for the activity, kept across a rotation (SakViewModel)
class TodoViewModel(
    application: Application,
) : SakViewModel<TodoModel>(application, app = "todo", selfUpdate = BuildConfig.SELF_UPDATE) {
    override val model =
        TodoModel(
            store = store,
            apiOf = { url, token -> TodoApi(client, url, token, languages) },
            scope = viewModelScope,
            allowUrl = BuildConfig.DEBUG,
            appVersion = BuildConfig.VERSION_NAME,
            updater = updater,
        ).started()
}
