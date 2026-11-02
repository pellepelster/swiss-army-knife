package io.pelle.sak.backend.files

import io.pelle.sak.model.files.StoredFile
import io.pelle.sak.openrouter.SpeechTranscriber
import io.pelle.sak.openrouter.TextRecognitionException
import io.pelle.sak.openrouter.TextRecognizer
import io.pelle.sak.storage.BlobContent
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

    // the file would go to the AI provider, but the owner asked for too many texts lately
    data object Limited : DiscoverTextResult

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
    // whether a text can be discovered now: the AI features turned on and an AI provider set up
    // (AN-26); what every user of the notes may know, the switches themselves are for the admins
    fun available(): Boolean = aiEnabled() && (recognizer() != null || transcriber() != null)

    // the text in a file of the owner, the address that uploaded it; the text once discovered is
    // answered even when nothing is configured any more or the AI features are turned off.
    // mayAsk is asked only right before the file goes to the AI provider, so a limit of what that
    // costs counts nothing else (e.g. a known text, an unknown file, AI turned off)
    fun discover(
        owner: String,
        id: Int,
        mayAsk: () -> Boolean = { true },
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
        val read: (BlobContent) -> String =
            if (image) {
                recognizer()?.let { { content -> it.recognize(content, file.contentType) } }
            } else {
                transcriber()?.let { { content -> it.transcribe(content, file.contentType) } }
            } ?: return DiscoverTextResult.NotConfigured
        val content = files.content(file) ?: return DiscoverTextResult.Failed(IOException("the content of file ${file.id} is not in the storage"))
        if (!mayAsk()) return DiscoverTextResult.Limited
        val text =
            try {
                read(content).trim()
            } catch (e: TextRecognitionException) {
                return DiscoverTextResult.Failed(e)
            } catch (e: IOException) {
                return DiscoverTextResult.Failed(e)
            }
        files.setDiscovered(file, text)
        return DiscoverTextResult.Discovered(text)
    }
}
