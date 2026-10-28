package io.pelle.sak.backend.redirects

import io.pelle.sak.backend.redirects.model.RedirectTarget
import io.pelle.sak.api.installApi
import io.pelle.sak.backend.redirects.model.RedirectResponse
import io.pelle.sak.backend.redirects.model.CreateRedirectRequest
import io.pelle.sak.backend.redirects.model.UpdateRedirectRequest
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.errors
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.respondErrors
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.config.SakConfig
import io.pelle.sak.backend.redirects.CreateRedirectResult
import io.pelle.sak.backend.redirects.DeleteRedirectResult
import io.pelle.sak.backend.redirects.UpdateRedirectResult
import io.pelle.sak.model.ingress.Redirect
import io.pelle.sak.backend.redirects.RedirectService
import io.pelle.sak.model.ingress.RedirectTarget as TargetOfRedirect
import io.pelle.sak.backend.hosting.S3BucketService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

// the domains the controller serves itself, no bucket is served at them and none is redirected
fun reservedDomains(config: SakConfig): Set<String> = setOf(config.apiHost, config.frontendHost)

// the domains every bucket is served at
fun S3BucketService.domains(): Map<String, List<String>> = all().associate { it.name to servedDomains(it) }

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

private fun TargetOfRedirect.response(): RedirectTarget =
    when (this) {
        is TargetOfRedirect.Domain -> RedirectTarget.Domain(domain)
        is TargetOfRedirect.Bucket -> RedirectTarget.Bucket(bucket)
    }

private fun RedirectTarget.target(): TargetOfRedirect =
    when (this) {
        is RedirectTarget.Domain -> TargetOfRedirect.Domain(domain)
        is RedirectTarget.Bucket -> TargetOfRedirect.Bucket(bucket)
    }

// the redirects caddy answers, meant to be mounted behind the authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.redirects(redirectService: RedirectService) {
    installApi()

    // the location of a bucket is read from the database, the response is made on the io dispatcher
    suspend fun Redirect.response() =
        withContext(Dispatchers.IO) {
            RedirectResponse(sourceDomain, target.response(), redirectService.location(target), createdAt.toString(), updatedAt?.toString())
        }

    get {
        val page = call.pageRequest() ?: return@get
        val redirects = page.of(withContext(Dispatchers.IO) { redirectService.all() })
        call.respond(page.of(redirects.items.map { it.response() }, redirects.pagination.total))
    }.describe {
        tag(INGRESS_TAG)
        summary = "The redirected domains"
        parameters { pagination() }
        responses {
            HttpStatusCode.OK {
                description = "A page of the redirects, each with its target and the domain it leads to"
                schema = jsonSchema<PageResponse<RedirectResponse>>()
            }
            errors(HttpStatusCode.BadRequest, "An invalid page")
        }
    }

    post {
        val request = call.receive<CreateRedirectRequest>()
        when (val result = withContext(Dispatchers.IO) { redirectService.create(request.sourceDomain, request.target.target()) }) {
            is CreateRedirectResult.Created -> call.respond(HttpStatusCode.Created, result.redirect.response())
            CreateRedirectResult.InvalidDomain ->
                call.respondErrors(
                    "Both domains must be valid and differ, and the redirected one must be neither one of the apps or the api nor one of a bucket.",
                )
            CreateRedirectResult.Exists -> call.respondErrors("The domain is redirected already.", status = HttpStatusCode.Conflict)
            CreateRedirectResult.UnknownBucket ->
                call.respondErrors("There is no such bucket.", status = HttpStatusCode.UnprocessableEntity)
            CreateRedirectResult.BucketNotServed ->
                call.respondErrors("The bucket is not served at a domain.", status = HttpStatusCode.UnprocessableEntity)
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
            errors(
                HttpStatusCode.BadRequest,
                "No valid domain, the source is the target, a domain the controller or a bucket serves, or a malformed request",
            )
            errors(HttpStatusCode.Conflict, "The domain is redirected already")
            errors(HttpStatusCode.UnprocessableEntity, "The bucket is unknown or not served at a domain")
        }
    }

    get("/{source}") {
        when (val redirect = withContext(Dispatchers.IO) { redirectService.byId(call.parameters["source"].orEmpty()) }) {
            null -> call.respond(HttpStatusCode.NotFound)
            else -> call.respond(redirect.response())
        }
    }.describe {
        tag(INGRESS_TAG)
        summary = "A redirected domain"
        parameters {
            path("source") {
                description = "The redirected domain"
                required = true
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "The redirect with its target and the domain it leads to"
                schema = jsonSchema<RedirectResponse>()
            }
            HttpStatusCode.NotFound { description = "The domain is not redirected" }
        }
    }

    put("/{source}") {
        val request = call.receive<UpdateRedirectRequest>()
        val source = call.parameters["source"].orEmpty()
        when (val result = withContext(Dispatchers.IO) { redirectService.update(source, request.target.target()) }) {
            is UpdateRedirectResult.Updated -> call.respond(result.redirect.response())
            UpdateRedirectResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            UpdateRedirectResult.InvalidDomain ->
                call.respondErrors("The domain redirected to must be valid and differ from the redirected one.")
            UpdateRedirectResult.UnknownBucket ->
                call.respondErrors("There is no such bucket.", status = HttpStatusCode.UnprocessableEntity)
            UpdateRedirectResult.BucketNotServed ->
                call.respondErrors("The bucket is not served at a domain.", status = HttpStatusCode.UnprocessableEntity)
        }
    }.describe {
        tag(INGRESS_TAG)
        summary = "Change where a domain is redirected to"
        description = "The redirected domain stays, its target is replaced. Caddy is configured right away."
        parameters {
            path("source") {
                description = "The redirected domain"
                required = true
                schema = jsonSchema<String>()
            }
        }
        requestBody {
            required = true
            schema = jsonSchema<UpdateRedirectRequest>()
        }
        responses {
            HttpStatusCode.OK {
                description = "The changed redirect"
                schema = jsonSchema<RedirectResponse>()
            }
            errors(HttpStatusCode.BadRequest, "No valid domain, the redirected domain itself, or a malformed request")
            HttpStatusCode.NotFound { description = "The domain is not redirected" }
            errors(HttpStatusCode.UnprocessableEntity, "The bucket is unknown or not served at a domain")
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
