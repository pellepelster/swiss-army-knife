package io.pelle.sak.backend.deployment

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.backend.deployment.model.api.DeploymentResponse
import io.pelle.sak.backend.deployment.model.api.SmtpConfigResponse
import io.pelle.sak.backend.info.model.api.DnsModeResponse
import io.pelle.sak.backend.info.model.api.UserModeResponse
import io.pelle.sak.backend.users.UserService
import io.pelle.sak.config.SakConfig

private const val DEPLOYMENT_TAG = "config"

fun SakConfig.SmtpConfig.response() =
    SmtpConfigResponse(host, port, username, !password.isNullOrEmpty(), password?.takeIf { it.isNotEmpty() }, from, tls, starttls)

// mounted behind the signature of sakadmin only and the admin role: a redeploy reads them again, the
// password of the mail server included
@OptIn(ExperimentalKtorApi::class)
fun Route.deployment(config: SakConfig) {
    get {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val smtp = config.auth?.smtp
        call.respond(
            DeploymentResponse(
                name = config.name,
                dnsMode = DnsModeResponse.of(config.dnsMode),
                rootDomain = config.rootDomain,
                userMode = UserModeResponse.of(smtp != null),
                adminEmail = config.auth?.admins?.firstOrNull()?.let(UserService::normalize),
                smtp = smtp?.response(),
                acmeCa = config.acmeCa,
            ),
        )
    }.describe {
        tag(DEPLOYMENT_TAG)
        summary = "The settings of the deployment"
        description =
            "The name, the dns mode, the root domain, the user mode, the admin, the mail server with its password and the " +
            "acme ca the deployment runs with, so sakadmin can deploy it again with them. Only for a request signed by sakadmin, no session."
        responses {
            HttpStatusCode.OK {
                description = "The settings of the deployment"
                schema = jsonSchema<DeploymentResponse>()
            }
        }
    }
}
