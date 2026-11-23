package io.pelle.sak.backend.redirects

import io.pelle.sak.backend.respondDeleted
import io.pelle.sak.backend.DeleteResult
import io.pelle.sak.auth.owner
import io.pelle.sak.api.apiTime
import io.pelle.sak.backend.redirects.model.RedirectTargetRequest
import io.pelle.sak.backend.redirects.model.RedirectTargetResponse
import io.pelle.sak.backend.redirects.model.RedirectResponse
import io.pelle.sak.backend.redirects.model.CreateRedirectRequest
import io.pelle.sak.backend.redirects.model.UpdateRedirectRequest
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.errors
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.respondErrors
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
import io.pelle.sak.model.redirects.Redirect
import io.pelle.sak.model.redirects.RedirectTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val INGRESS_TAG = "ingress"

private fun RedirectTarget.response(): RedirectTargetResponse =
    when (this) {
        is RedirectTarget.Domain -> RedirectTargetResponse.Domain(domain)
        is RedirectTarget.Bucket -> RedirectTargetResponse.Bucket(bucket)
    }

private fun RedirectTargetRequest.target(): RedirectTarget =
    when (this) {
        is RedirectTargetRequest.Domain -> RedirectTarget.Domain(domain)
        is RedirectTargetRequest.Bucket -> RedirectTarget.Bucket(bucket)
    }

// mounted behind the authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.redirects(redirectService: RedirectService) {
    // the location of a bucket is read from the database
    suspend fun Redirect.response() =
        withContext(Dispatchers.IO) {
            RedirectResponse(sourceDomain, target.response(), redirectService.location(target), createdAt.apiTime(), updatedAt?.apiTime())
        }

    get {
        val page = call.pageRequest() ?: return@get
        val redirects = page.of(withContext(Dispatchers.IO) { redirectService.all() })
        call.respond(page.of(redirects.map { it.response() }))
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
        when (val result = withContext(Dispatchers.IO) { redirectService.create(request.sourceDomain, request.target.target(), call.owner) }) {
            is RedirectCreateResult.Created -> call.respond(HttpStatusCode.Created, result.redirect.response())
            is RedirectCreateResult.Invalid -> call.respondErrors(result.errors)
            RedirectCreateResult.Exists -> call.respondErrors(RedirectsTexts.EXISTS, status = HttpStatusCode.Conflict)
            RedirectCreateResult.UnknownBucket ->
                call.respondErrors(RedirectsTexts.UNKNOWN_BUCKET, status = HttpStatusCode.UnprocessableEntity)
            RedirectCreateResult.BucketNotServed ->
                call.respondErrors(RedirectsTexts.BUCKET_NOT_SERVED, status = HttpStatusCode.UnprocessableEntity)
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
        when (val redirect = withContext(Dispatchers.IO) { redirectService.bySourceDomain(call.parameters["source"].orEmpty()) }) {
            null -> call.respond(HttpStatusCode.NotFound)
            else -> call.respond(redirect.response())
        }
    }.describe {
        tag(INGRESS_TAG)
        summary = "A redirected domain"
        parameters {
            sourceDomain()
        }
        responses {
            HttpStatusCode.OK {
                description = "The redirect with its target and the domain it leads to"
                schema = jsonSchema<RedirectResponse>()
            }
            errors(HttpStatusCode.NotFound, "The domain is not redirected")
        }
    }

    put("/{source}") {
        val request = call.receive<UpdateRedirectRequest>()
        val source = call.parameters["source"].orEmpty()
        when (val result = withContext(Dispatchers.IO) { redirectService.update(source, request.target.target()) }) {
            is RedirectUpdateResult.Updated -> call.respond(result.redirect.response())
            RedirectUpdateResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            is RedirectUpdateResult.Invalid -> call.respondErrors(result.errors)
            RedirectUpdateResult.UnknownBucket ->
                call.respondErrors(RedirectsTexts.UNKNOWN_BUCKET, status = HttpStatusCode.UnprocessableEntity)
            RedirectUpdateResult.BucketNotServed ->
                call.respondErrors(RedirectsTexts.BUCKET_NOT_SERVED, status = HttpStatusCode.UnprocessableEntity)
        }
    }.describe {
        tag(INGRESS_TAG)
        summary = "Change where a domain is redirected to"
        description = "The redirected domain stays, its target is replaced. Caddy is configured right away."
        parameters {
            sourceDomain()
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
            errors(HttpStatusCode.NotFound, "The domain is not redirected")
            errors(HttpStatusCode.UnprocessableEntity, "The bucket is unknown or not served at a domain")
        }
    }

    delete("/{source}") {
        call.respondDeleted(withContext(Dispatchers.IO) { redirectService.delete(call.parameters["source"].orEmpty()) })
    }.describe {
        tag(INGRESS_TAG)
        summary = "Stop redirecting a domain"
        parameters {
            sourceDomain()
        }
        responses {
            HttpStatusCode.NoContent { description = "The redirect is gone" }
            errors(HttpStatusCode.NotFound, "The domain is not redirected")
        }
    }
}

private fun io.ktor.openapi.Parameters.Builder.sourceDomain() =
    path("source") {
        description = "The redirected domain"
        required = true
        schema = jsonSchema<String>()
    }
