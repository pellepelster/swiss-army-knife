package io.pelle.sak.todo

import android.app.Application
import io.pelle.sak.core.SakViewModel
import io.pelle.sak.todo.api.TodoApi

class TodoViewModel(
    application: Application,
) : SakViewModel<TodoModel>(
        application,
        app = "todo",
        selfUpdate = BuildConfig.SELF_UPDATE,
        allowUrl = BuildConfig.DEBUG,
        appVersion = BuildConfig.VERSION_NAME,
    ) {
    override val model =
        TodoModel(
            setup,
            apiOf = { url, token -> TodoApi(client, url, token, languages) },
        ).started()
}
