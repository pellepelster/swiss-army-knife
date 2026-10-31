package io.pelle.sak.auth.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// what asked for a login link: the web app, or the android app of the grocery lists, whose link the
// signin page of the web app hands on to the app instead of signing in itself (FDR-005)
@Serializable
enum class LoginClient {
    @SerialName("web")
    WEB,

    @SerialName("android")
    ANDROID,
}
