package io.pelle.sak.backend.info

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.api.installApi
import io.pelle.sak.backend.info.model.InfoResponse
import io.pelle.sak.config.DnsMode

private const val INFO_TAG = "info"

// the info of a deployment, without a session: which deployment answers at a domain, e.g. after
// its dns records were set up; it tells the name, the dns mode and the user mode and nothing else.
// The user mode follows from the mail server: with one the users sign in by e-mail (multi), without
// one only the admin signs in (single)
@OptIn(ExperimentalKtorApi::class)
fun Route.info(
    name: String,
    dnsMode: DnsMode,
    mailServer: Boolean,
) {
    installApi()

    get {
        call.respond(InfoResponse(name, dnsMode.value, if (mailServer) "multi" else "single"))
    }.describe {
        tag(INFO_TAG)
        summary = "The deployment that answers"
        description =
            "The name, the dns mode (domain or subdomain) and the user mode (multi with a mail server, single without) " +
            "of the deployment the backend belongs to. Needs no session."
        responses {
            HttpStatusCode.OK {
                description = "The name, the dns mode and the user mode of the deployment"
                schema = jsonSchema<InfoResponse>()
            }
        }
    }
}
