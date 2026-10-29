package io.pelle.sak.backend.config

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.api.installApi
import io.pelle.sak.backend.config.model.ConfigResponse
import io.pelle.sak.backend.config.model.SmtpConfigResponse
import io.pelle.sak.config.SakConfig

private const val CONFIG_TAG = "config"

fun SakConfig.SmtpConfig.response() =
    SmtpConfigResponse(host, port, username, !password.isNullOrEmpty(), password?.takeIf { it.isNotEmpty() }, from, tls, starttls)

// the running configuration, meant to be mounted behind the authentication and the admin role; the
// one secret it answers is the password of the mail server, so a redeploy need not ask for it again
@OptIn(ExperimentalKtorApi::class)
fun Route.config(config: SakConfig) {
    installApi()

    get {
        call.respond(ConfigResponse(config.auth?.smtp?.response(), config.auth?.admins.orEmpty()))
    }.describe {
        tag(CONFIG_TAG)
        summary = "The running configuration"
        description = "The mail server the login links are sent with, its password included, and the addresses of the e-mail login. Admins only."
        responses {
            HttpStatusCode.OK {
                description = "The running configuration"
                schema = jsonSchema<ConfigResponse>()
            }
        }
    }
}
