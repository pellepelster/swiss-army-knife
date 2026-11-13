package io.pelle.sak.auth.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// what asked for a login link; the signin page hands the link of an android app on to that app. A
// closed list, so a link never names another package. linkName is the client parameter of the link
@Serializable
enum class LoginClient(
    val linkName: String?,
) {
    @SerialName("web")
    WEB(null),

    @SerialName("grocery-android")
    GROCERY_ANDROID("grocery-android"),

    @SerialName("notes-android")
    NOTES_ANDROID("notes-android"),

    @SerialName("todo-android")
    TODO_ANDROID("todo-android"),
}
