package io.pelle.sak.backend.info.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// whether the users sign in with a link by e-mail (a mail server) or only the admin with the password
@Serializable
enum class UserModeResponse {
    @SerialName("single")
    SINGLE,

    @SerialName("multi")
    MULTI,
    ;

    companion object {
        // multi with a mail server for the login links, single without one
        fun of(mailServer: Boolean) = if (mailServer) MULTI else SINGLE
    }
}
