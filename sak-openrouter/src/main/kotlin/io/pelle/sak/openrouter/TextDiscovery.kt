package io.pelle.sak.openrouter

import io.pelle.sak.storage.BlobContent

// reads the text in an image, e.g. a vision model
interface TextRecognizer {
    // the text in the image file, empty when there is none; throws TextRecognitionException
    fun recognize(
        image: BlobContent,
        contentType: String,
    ): String
}


// writes down what is said in a recording, e.g. a speech to text model
interface SpeechTranscriber {
    // the text of the recording file, empty when nothing is said; throws TextRecognitionException
    fun transcribe(
        audio: BlobContent,
        contentType: String,
    ): String
}


class TextRecognitionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
