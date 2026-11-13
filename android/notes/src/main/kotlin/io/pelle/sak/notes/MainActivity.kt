package io.pelle.sak.notes

import android.content.Intent
import android.net.Uri
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewModelScope
import io.pelle.sak.core.SakActivity
import io.pelle.sak.notes.ui.NotesApp
import kotlinx.coroutines.async

// core takes the login links; the activity takes the photos another app shares, they become a new note
class MainActivity : SakActivity() {
    private val viewModel: NotesViewModel by viewModels()

    override val signinScheme = "io.pelle.sak.notes"

    override val sakModel get() = viewModel.model

    override val appName = R.string.notes_app_name

    @Composable
    override fun Content() = NotesApp(viewModel.model)

    // read at once: a shared photo is readable only while the activity that got it lives. Scaled one
    // after the other (each decoded is many MB), only the ones a note takes, in the scope of the view
    // model, which a rotation keeps; the model says so for the ones left out
    override fun receive(intent: Intent): Boolean {
        val streams =
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            }
        val shared = shared(intent.action, intent.type, streams, intent.getCharSequenceExtra(Intent.EXTRA_TEXT)) ?: return false
        val attached = shared.photos.take(MAX_PHOTOS)
        val scaled = viewModel.viewModelScope.async { attached.map { uri -> scaledPhoto(applicationContext, uri) } }
        val reads: List<suspend () -> ByteArray?> =
            attached.indices.map { index -> suspend { scaled.await()[index] } } +
                List(shared.photos.size - attached.size) { suspend { null } }
        viewModel.model.createWithPhotos(reads, shared.text)
        return true
    }

    // the notes have no stream of changes: loaded again when the app comes back to the front, the typed
    // text saved when it goes; a rotation does neither, so a recording goes on and an undo stays
    override fun onRestart() {
        super.onRestart()
        viewModel.model.resume()
    }

    override fun background() = viewModel.model.pause()
}
