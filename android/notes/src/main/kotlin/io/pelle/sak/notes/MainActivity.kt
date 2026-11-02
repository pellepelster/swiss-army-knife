package io.pelle.sak.notes

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import io.pelle.sak.core.SakActivity
import io.pelle.sak.notes.ui.NotesApp

// the one activity; core takes the login links (io.pelle.sak.notes://signin?token=…)
class MainActivity : SakActivity() {
    private val viewModel: NotesViewModel by viewModels()

    override val signinScheme = "io.pelle.sak.notes"

    override val sakModel get() = viewModel.model

    @Composable
    override fun Content() = NotesApp(viewModel.model)

    // the notes have no stream of changes: loaded again when the app comes to the front, the text
    // typed saved when it goes
    override fun onRestart() {
        super.onRestart()
        viewModel.model.resume()
    }

    override fun onStop() {
        viewModel.model.pause()
        super.onStop()
    }
}
