package io.pelle.sak.grocery

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import io.pelle.sak.core.SakActivity
import io.pelle.sak.grocery.ui.GroceryApp

// the one activity; core takes the login links (io.pelle.sak.grocery://signin?token=…)
class MainActivity : SakActivity() {
    private val viewModel: GroceryViewModel by viewModels()

    override val signinScheme = "io.pelle.sak.grocery"

    override val sakModel get() = viewModel.model

    override val appName = R.string.grocery_app_name

    @Composable
    override fun Content() = GroceryApp(viewModel.model)

    // the changes of the lists only while the app is seen (LIST-4); a check that waits is sent when
    // the app goes (AN-29), not on a rotation, its undo stays (SakActivity)
    override fun foreground() = viewModel.model.startListening()

    override fun background() = viewModel.model.stopped()
}
