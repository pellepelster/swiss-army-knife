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

class TextRecognitionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

sealed interface DiscoverTextResult {
    data class Discovered(
        val text: String,
    ) : DiscoverTextResult

    data object NotFound : DiscoverTextResult

    // only the text in images is discovered
    data object NotAnImage : DiscoverTextResult

    // no recognizer is configured
    data object NotConfigured : DiscoverTextResult

    data class Failed(
        val cause: TextRecognitionException,
    ) : DiscoverTextResult
}

// discovers the text in the images of the file storage; a file never changes under its sha-256,
// so the text is kept with the file (StoredFile.discovered) and the file is not read again
class FileTextService(
    private val files: FileService,
    private val recognizer: TextRecognizer?,
) {
    val configured: Boolean get() = recognizer != null

    fun discover(sha256: String): DiscoverTextResult {
        val file = files.bySha256(sha256) ?: return DiscoverTextResult.NotFound
        if (!file.contentType.startsWith("image/")) return DiscoverTextResult.NotAnImage
        val recognizer = recognizer ?: return DiscoverTextResult.NotConfigured
        file.discovered?.let { return DiscoverTextResult.Discovered(it) }
        val text =
            try {
                recognizer.recognize(Files.readAllBytes(files.path(file)), file.contentType).trim()
            } catch (e: TextRecognitionException) {
                return DiscoverTextResult.Failed(e)
            }
        files.setDiscovered(file, text)
        return DiscoverTextResult.Discovered(text)
    }
}
