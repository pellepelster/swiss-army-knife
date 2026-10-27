package io.pelle.sak.openrouter

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
