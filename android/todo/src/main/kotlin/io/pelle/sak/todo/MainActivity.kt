package io.pelle.sak.todo

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import io.pelle.sak.core.SakActivity
import io.pelle.sak.todo.ui.TodoApp

// the one activity; core takes the login links (io.pelle.sak.todo://signin?token=…)
class MainActivity : SakActivity() {
    private val viewModel: TodoViewModel by viewModels()

    override val signinScheme = "io.pelle.sak.todo"

    override val sakModel get() = viewModel.model

    @Composable
    override fun Content() = TodoApp(viewModel.model)

    // the changes of the lists only while the app is seen (LIST-4); a purge that waits is sent when the
    // app goes, not on a rotation, its undo stays (SakActivity)
    override fun foreground() = viewModel.model.startListening()

    override fun background() = viewModel.model.stopped()
}
