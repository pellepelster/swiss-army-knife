package io.pelle.sak.model.files

import java.nio.file.Files

// reads the text in an image, e.g. a vision model
interface TextRecognizer {
    // the text in the image, empty when there is none; throws TextRecognitionException
    fun recognize(
        image: ByteArray,
        contentType: String,
    ): String
}

// writes down what is said in a recording, e.g. a speech to text model
interface SpeechTranscriber {
    // the text of the recording, empty when nothing is said; throws TextRecognitionException
    fun transcribe(
        audio: ByteArray,
        contentType: String,
    ): String
}

class TextRecognitionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

sealed interface DiscoverTextResult {
    data class Discovered(
        val text: String,
    ) : DiscoverTextResult

    data object NotFound : DiscoverTextResult

    // only the text in images and recordings is discovered
    data object Unsupported : DiscoverTextResult

    // nothing is configured to discover the text in files of the type
    data object NotConfigured : DiscoverTextResult

    data class Failed(
        val cause: TextRecognitionException,
    ) : DiscoverTextResult
}

// discovers the text in the images and the recordings of the file storage; a file never changes
// under its id, so the text is kept with the file (StoredFile.discovered) and the file is
// not read again
class FileTextService(
    private val files: FileService,
    private val recognizer: TextRecognizer?,
    private val transcriber: SpeechTranscriber? = null,
) {
    fun discover(id: Int): DiscoverTextResult {
        val file = files.byId(id) ?: return DiscoverTextResult.NotFound
        val read: (ByteArray) -> String =
            when {
                file.contentType.startsWith("image/") -> recognizer?.let { { bytes -> it.recognize(bytes, file.contentType) } }
                file.contentType.startsWith("audio/") -> transcriber?.let { { bytes -> it.transcribe(bytes, file.contentType) } }
                else -> return DiscoverTextResult.Unsupported
            } ?: return DiscoverTextResult.NotConfigured
        file.discovered?.let { return DiscoverTextResult.Discovered(it) }
        val text =
            try {
                read(Files.readAllBytes(files.path(file))).trim()
            } catch (e: TextRecognitionException) {
                return DiscoverTextResult.Failed(e)
            }
        files.setDiscovered(file, text)
        return DiscoverTextResult.Discovered(text)
    }
}
