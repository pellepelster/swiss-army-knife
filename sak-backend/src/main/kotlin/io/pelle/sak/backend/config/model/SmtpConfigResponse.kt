package io.pelle.sak.backend.config.model

import kotlinx.serialization.Serializable

// the mail server of the login links, without its password
@Serializable
data class SmtpConfigResponse(
    val host: String,
    val port: Int,
    // none when the server takes mail without a login
    val username: String?,
    // whether a password is configured, the password itself never leaves the backend
    val passwordSet: Boolean,
    // the sender of the login mails
    val from: String,
    // implicit tls (smtps)
    val tls: Boolean,
    // the plain connection upgraded with starttls
    val starttls: Boolean,
)
