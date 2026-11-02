package io.pelle.sak.auth.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// what asked for a login link: the web app, or one of the android apps, whose link the signin page
// of the web app hands on to that app instead of signing in itself; a closed list, so a link never
// names another package. The name is the client parameter of the link (null for the web app)
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
}
