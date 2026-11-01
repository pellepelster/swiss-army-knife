package io.pelle.sak.backend.config.model

import kotlinx.serialization.Serializable

// the settings the deployment was created or deployed with, as far as the backend has them: what
// sakctl fills a redeploy with
@Serializable
data class DeploymentResponse(
    val name: String,
    // domain or subdomain
    val dnsMode: String,
    val rootDomain: String,
    // multi with a mail server, single without
    val userMode: String,
    // the admin who also signs in by e-mail, none without one
    val adminEmail: String?,
    // the mail server of the login links, its password included; none for single user
    val smtp: SmtpConfigResponse?,
    // the acme directory of the certificates, none for the production ca of let's encrypt
    val acmeCa: String?,
)
