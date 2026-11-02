package io.pelle.sak.notes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.pelle.sak.core.ui.SakTheme
import io.pelle.sak.notes.ui.NotesApp

// the scheme of the login link, the package of the release; the debug build has a package of its
// own (.debug) and the same scheme
private const val SIGNIN_SCHEME = "io.pelle.sak.notes"

// the one activity; it takes the login links the signin page of the web app hands on
// (io.pelle.sak.notes://signin?token=…)
class MainActivity : ComponentActivity() {
    private val viewModel: NotesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // not again after a rotation, the link was used
        if (savedInstanceState == null) signIn(intent)
        setContent {
            SakTheme {
                NotesApp(viewModel.model)
            }
        }
    }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        signIn(intent)
    }

    private fun signIn(intent: Intent?) {
        val link = intent?.data ?: return
        if (link.scheme != SIGNIN_SCHEME || link.host != "signin") return
        link.getQueryParameter("token")?.takeIf { it.isNotEmpty() }?.let { viewModel.model.signInWithLink(it) }
        // the token is used once, a recreated activity must not find it again
        setIntent(Intent(intent).setData(null))
    }
}
