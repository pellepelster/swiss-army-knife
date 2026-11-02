package io.pelle.sak.openrouter

import java.nio.file.Path

// reads the text in an image, e.g. a vision model
interface TextRecognizer {
    // the text in the image file, empty when there is none; throws TextRecognitionException
    fun recognize(
        image: Path,
        contentType: String,
    ): String
}


// writes down what is said in a recording, e.g. a speech to text model
interface SpeechTranscriber {
    // the text of the recording file, empty when nothing is said; throws TextRecognitionException
    fun transcribe(
        audio: Path,
        contentType: String,
    ): String
}


class TextRecognitionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
