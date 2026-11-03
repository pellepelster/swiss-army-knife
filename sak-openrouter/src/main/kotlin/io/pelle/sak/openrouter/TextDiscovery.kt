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


// an ingredient of a recipe as the model reads it, unchecked: the app checks it against its limits
data class Ingredient(
    val name: String,
    // null when the recipe gives none, e.g. Salz
    val quantity: Double?,
    // null for a count of things, e.g. 2 Zwiebeln
    val unit: String?,
)

// reads the ingredients of a recipe in an image, e.g. a vision model
interface IngredientRecognizer {
    // the ingredients of the recipe the image shows, empty when it shows none; throws
    // TextRecognitionException
    fun ingredients(
        image: BlobContent,
        contentType: String,
    ): List<Ingredient>
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
