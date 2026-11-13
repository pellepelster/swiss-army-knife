package io.pelle.sak.backend.info.model

import io.pelle.sak.config.DnsMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// the deployment the backend belongs to
@Serializable
data class InfoResponse(
    val name: String,
    // domain or subdomain
    val dnsMode: DnsMode,
    // multi with a mail server for the login links, single without one
    val userMode: UserMode,
    // the version of sak it runs, e.g. 0.1.15, none without one; the android apps update themselves to it
    val version: String?,
)

// whether the users sign in with a link by e-mail (a mail server) or only the admin with the password
@Serializable
enum class UserMode {
    @SerialName("single")
    SINGLE,

    @SerialName("multi")
    MULTI,
}
