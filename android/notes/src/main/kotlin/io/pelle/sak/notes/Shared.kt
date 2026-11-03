package io.pelle.sak.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.pelle.sak.core.ui.PhotoScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// what another app shares with the notes: its photos (content uris) and a text, e.g. a caption
data class Shared<T>(
    val photos: List<T>,
    val text: String,
)

// the photos of a share, SEND with one (EXTRA_STREAM), SEND_MULTIPLE with several, and its text
// (EXTRA_TEXT); null for any other intent and for one without a photo. Only images: the intent filters
// of the manifest take image/* only, an intent that names this activity may still carry another type
fun <T> shared(
    action: String?,
    type: String?,
    streams: List<T?>,
    text: CharSequence?,
): Shared<T>? {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    if (type?.startsWith("image/") != true) return null
    val photos = streams.filterNotNull()
    if (photos.isEmpty()) return null
    return Shared(photos, text?.toString().orEmpty())
}

// a photo the camera took, the user chose or another app shared, scaled by PhotoScaling of core; null
// when it can not be read
suspend fun scaledPhoto(
    context: Context,
    uri: Uri,
): ByteArray? =
    withContext(Dispatchers.IO) {
        try {
            PhotoScaling.jpeg(context, uri)
        } catch (e: Exception) {
            Log.w("sak-notes", "the photo $uri could not be read", e)
            null
        }
    }
