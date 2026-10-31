package io.pelle.sak.backend.config.model

import kotlinx.serialization.Serializable

// the mail server of the login links, its password included: sakctl takes it over on a redeploy
// (docs/development/ACCEPTED-RISKS.md)
@Serializable
data class SmtpConfigResponse(
    val host: String,
    val port: Int,
    // none when the server takes mail without a login
    val username: String?,
    // whether a password is configured
    val passwordSet: Boolean,
    // the password at the mail server, none without one
    val password: String?,
    // the sender of the login mails
    val from: String,
    // implicit tls (smtps)
    val tls: Boolean,
    // the plain connection upgraded with starttls
    val starttls: Boolean,
)
