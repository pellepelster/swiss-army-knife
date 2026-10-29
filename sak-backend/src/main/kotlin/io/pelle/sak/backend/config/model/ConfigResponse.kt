package io.pelle.sak.backend.config.model

import kotlinx.serialization.Serializable

// the running configuration of the backend, as far as an admin may see it
@Serializable
data class ConfigResponse(
    // the mail server the login links are sent with, none without the login
    val smtp: SmtpConfigResponse?,
    // the addresses of the e-mail login, the first the admin; none for a single user deployment
    val admins: List<String> = emptyList(),
)
