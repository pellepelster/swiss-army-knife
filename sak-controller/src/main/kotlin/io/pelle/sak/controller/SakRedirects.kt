package io.pelle.sak.controller

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.config.SakConfig
import io.pelle.sak.model.ingress.CreateRedirectResult
import io.pelle.sak.model.ingress.DeleteRedirectResult
import io.pelle.sak.model.ingress.Redirect
import io.pelle.sak.model.ingress.RedirectService
import io.pelle.sak.model.ingress.RedirectTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

// the domains the controller serves itself, none of them can be redirected
fun reservedDomains(config: SakConfig): Set<String> =
    setOf(config.rootDomain, s3Host(config), config.consoleHost) + config.s3.buckets.values.flatMap { it.domains }

// every redirected domain with the domain it leads to; a bucket no longer served at a domain
// leads nowhere, its redirect is left out
fun RedirectService.locations(): Map<String, String> =
    all()
        .mapNotNull { redirect ->
            val location = location(redirect.target)
            if (location == null) logger.warn { "the redirect of '${redirect.sourceDomain}' leads nowhere, it is left out" }
            location?.let { redirect.sourceDomain to it }
        }.toMap()

private const val INGRESS_TAG = "ingress"

// where a redirect leads to, told apart by its type; more types will follow
@Serializable
sealed interface RedirectTargetJson {
    @Serializable
    @SerialName("domain")
    data class Domain(
        val domain: String,
    ) : RedirectTargetJson

    @Serializable
    @SerialName("bucket")
    data class Bucket(
        val bucket: String,
    ) : RedirectTargetJson
}

// location is the domain the redirect leads to now, missing for a bucket no longer served at a domain
@Serializable
data class RedirectResponse(
    val sourceDomain: String,
    val target: RedirectTargetJson,
    val location: String?,
    val createdAt: String,
)

@Serializable
data class CreateRedirectRequest(
    val sourceDomain: String,
    val target: RedirectTargetJson,
)

private fun RedirectTarget.json(): RedirectTargetJson =
    when (this) {
        is RedirectTarget.Domain -> RedirectTargetJson.Domain(domain)
        is RedirectTarget.Bucket -> RedirectTargetJson.Bucket(bucket)
    }

private fun RedirectTargetJson.target(): RedirectTarget =
    when (this) {
        is RedirectTargetJson.Domain -> RedirectTarget.Domain(domain)
        is RedirectTargetJson.Bucket -> RedirectTarget.Bucket(bucket)
    }

// the redirects caddy answers, meant to be mounted behind the console's authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.redirects(redirectService: RedirectService) {
    install(ContentNegotiation) { json() }

    fun Redirect.response() = RedirectResponse(sourceDomain, target.json(), redirectService.location(target), createdAt.toString())

    get {
        call.respond(withContext(Dispatchers.IO) { redirectService.all() }.map { it.response() })
    }.describe {
        tag(INGRESS_TAG)
        summary = "The redirected domains"
        responses {
            HttpStatusCode.OK {
                description = "The redirects, each with its target and the domain it leads to"
                schema = jsonSchema<List<RedirectResponse>>()
            }
        }
    }

    post {
        val request = call.receive<CreateRedirectRequest>()
        when (val result = withContext(Dispatchers.IO) { redirectService.create(request.sourceDomain, request.target.target()) }) {
            is CreateRedirectResult.Created -> call.respond(HttpStatusCode.Created, result.redirect.response())
            CreateRedirectResult.InvalidDomain -> call.respond(HttpStatusCode.BadRequest)
            CreateRedirectResult.Exists -> call.respond(HttpStatusCode.Conflict)
            CreateRedirectResult.UnknownBucket, CreateRedirectResult.BucketNotServed -> call.respond(HttpStatusCode.UnprocessableEntity)
        }
    }.describe {
        tag(INGRESS_TAG)
        summary = "Redirect a domain"
        description =
            "Redirects every request for the domain to an external domain, or to the first domain a bucket is served at. " +
            "Caddy is configured right away."
        requestBody {
            required = true
            schema = jsonSchema<CreateRedirectRequest>()
        }
        responses {
            HttpStatusCode.Created {
                description = "The new redirect"
                schema = jsonSchema<RedirectResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "No valid domain, the source is the target, or a domain the controller serves itself"
            }
            HttpStatusCode.Conflict { description = "The domain is redirected already" }
            HttpStatusCode.UnprocessableEntity { description = "The bucket is unknown or not served at a domain" }
        }
    }

    delete("/{source}") {
        when (withContext(Dispatchers.IO) { redirectService.delete(call.parameters["source"].orEmpty()) }) {
            DeleteRedirectResult.Deleted -> call.respond(HttpStatusCode.NoContent)
            DeleteRedirectResult.NotFound -> call.respond(HttpStatusCode.NotFound)
        }
    }.describe {
        tag(INGRESS_TAG)
        summary = "Stop redirecting a domain"
        parameters {
            path("source") {
                description = "The redirected domain"
                required = true
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.NoContent { description = "The redirect is gone" }
            HttpStatusCode.NotFound { description = "The domain is not redirected" }
        }
    }
}
