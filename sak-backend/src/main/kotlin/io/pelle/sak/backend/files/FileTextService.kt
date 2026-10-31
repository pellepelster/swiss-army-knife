package io.pelle.sak.backend.files

import io.pelle.sak.model.files.StoredFile
import io.pelle.sak.openrouter.SpeechTranscriber
import io.pelle.sak.openrouter.TextRecognitionException
import io.pelle.sak.openrouter.TextRecognizer
import java.nio.file.Files
import java.io.IOException

sealed interface DiscoverTextResult {
    data class Discovered(
        val text: String,
    ) : DiscoverTextResult

    data object NotFound : DiscoverTextResult

    // only the text in images and recordings is discovered
    data object Unsupported : DiscoverTextResult

    // nothing is set up to discover the text in files of the type, e.g. no api key
    data object NotConfigured : DiscoverTextResult

    // the AI features are turned off in the settings (NotesSettingsService.AI and what it requires)
    data object Disabled : DiscoverTextResult

    // the model failed, or the content of the file could not be read
    data class Failed(
        val cause: Exception,
    ) : DiscoverTextResult
}

// discovers the text in the images and the recordings of the file storage; a file never changes
// under its id, so the text is kept with the file (StoredFile.discovered) and the file is
// not read again
class FileTextService(
    private val files: FileService,
    // asked for each file, null while none is set up (OpenRouterManager)
    private val recognizer: () -> TextRecognizer?,
    private val transcriber: () -> SpeechTranscriber?,
    // whether the files may be sent to the AI provider at all
    private val aiEnabled: () -> Boolean,
) {
    // the text in a file of the owner, the address that uploaded it; the text once discovered is
    // answered even when nothing is configured any more or the AI features are turned off
    fun discover(
        owner: String,
        id: Int,
    ): DiscoverTextResult {
        val file = files.byId(owner, id) ?: return DiscoverTextResult.NotFound
        val image =
            when {
                file.contentType.startsWith("image/") -> true
                file.contentType.startsWith("audio/") -> false
                else -> return DiscoverTextResult.Unsupported
            }
        file.discovered?.let { return DiscoverTextResult.Discovered(it) }
        if (!aiEnabled()) return DiscoverTextResult.Disabled
        val read: (ByteArray) -> String =
            if (image) {
                recognizer()?.let { { bytes -> it.recognize(bytes, file.contentType) } }
            } else {
                transcriber()?.let { { bytes -> it.transcribe(bytes, file.contentType) } }
            } ?: return DiscoverTextResult.NotConfigured
        val text =
            try {
                read(Files.readAllBytes(files.path(file))).trim()
            } catch (e: TextRecognitionException) {
                return DiscoverTextResult.Failed(e)
            } catch (e: IOException) {
                return DiscoverTextResult.Failed(e)
            }
        files.setDiscovered(file, text)
        return DiscoverTextResult.Discovered(text)
    }
}
