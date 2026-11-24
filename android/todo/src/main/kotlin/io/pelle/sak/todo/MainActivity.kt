package io.pelle.sak.todo

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import io.pelle.sak.core.SakActivity
import io.pelle.sak.todo.ui.TodoApp

// the one activity; core takes the login links (io.pelle.sak.todo://signin?token=…)
class MainActivity : SakActivity() {
    private val viewModel: TodoViewModel by viewModels()

    override val signinScheme = "io.pelle.sak.todo"

    override val sakViewModel get() = viewModel

    override val appName = R.string.todo_app_name

    @Composable
    override fun Content() = TodoApp(viewModel.model)
}
