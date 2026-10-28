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
// its dns records were set up; it tells the sak name and the dns mode and nothing else
@OptIn(ExperimentalKtorApi::class)
fun Route.info(
    name: String,
    dnsMode: DnsMode,
) {
    installApi()

    get {
        call.respond(InfoResponse(name, dnsMode.value))
    }.describe {
        tag(INFO_TAG)
        summary = "The deployment that answers"
        description = "The sak name and the dns mode (domain or subdomain) of the deployment the backend belongs to. Needs no session."
        responses {
            HttpStatusCode.OK {
                description = "The sak name and the dns mode of the deployment"
                schema = jsonSchema<InfoResponse>()
            }
        }
    }
}
