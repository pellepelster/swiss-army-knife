package io.pelle.sak.backend.hosting

import io.pelle.sak.core.logCause
import io.pelle.sak.core.Text
import io.pelle.sak.auth.owner
import io.pelle.sak.api.Page
import io.pelle.sak.api.apiTime
import io.pelle.sak.api.installApi
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.errors
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.respondErrors
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.backend.hosting.model.BucketResponse
import io.pelle.sak.backend.hosting.model.BucketSecretResponse
import io.pelle.sak.backend.hosting.model.BucketStatisticsResponse
import io.pelle.sak.backend.hosting.model.CreateBucketRequest
import io.pelle.sak.backend.hosting.model.UpdateDomainsRequest
import io.pelle.sak.model.s3.S3Bucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

private const val S3_TAG = "s3"

// mounted behind the authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.buckets(
    bucketService: S3BucketService,
) {
    installApi()

    suspend fun S3Bucket.response() =
        BucketResponse(
            name,
            domains,
            bucketService.subdomain(name),
            createdAt.apiTime(),
            updatedAt?.apiTime(),
            bucketService.statisticsResponse(name),
        )

    get {
        val page = call.pageRequest() ?: return@get
        // the buckets of the page are counted at once, one not counted within a moment is still being counted
        val buckets = page.of(withContext(Dispatchers.IO) { bucketService.all() })
        val responses = coroutineScope { buckets.items.map { bucket -> async { bucket.response() } }.awaitAll() }
        call.respond(page.of(Page(responses, buckets.total)))
    }.describe {
        tag(S3_TAG)
        summary = "The buckets with the number of their objects and their size"
        description =
            "The numbers are counted in the background and kept, recounted after ${BucketStatisticsCache.REFRESH_AFTER.toMinutes()} minutes and after every write to a " +
            "bucket; a bucket not counted yet is still computing, ask again."
        parameters { pagination() }
        responses {
            HttpStatusCode.OK {
                description = "A page of the buckets with the domains they are served at and their statistics"
                schema = jsonSchema<PageResponse<BucketResponse>>()
            }
            errors(HttpStatusCode.BadRequest, "An invalid page")
        }
    }

    post {
        val request = call.receive<CreateBucketRequest>()
        when (val result = withContext(Dispatchers.IO) { bucketService.create(request.name, request.domains, call.owner) }) {
            is BucketCreateResult.Created -> call.respond(HttpStatusCode.Created, result.bucket.response())
            is BucketCreateResult.Invalid -> call.respondErrors(result.errors)
            BucketCreateResult.Exists -> call.respondErrors(HostingTexts.NAME_TAKEN, status = HttpStatusCode.Conflict)
            BucketCreateResult.SubdomainTaken -> call.respondErrors(HostingTexts.SUBDOMAIN_TAKEN, status = HttpStatusCode.Conflict)
            BucketCreateResult.DomainTaken -> call.respondErrors(HostingTexts.DOMAIN_TAKEN, status = HttpStatusCode.Conflict)
        }
    }.describe {
        tag(S3_TAG)
        summary = "Create a bucket"
        description =
            "Creates the bucket with a key pair that may access this bucket only, served at the domains in their order " +
            "and at <name>.<root_domain>. " +
            "The name can not be changed later."
        requestBody {
            required = true
            schema = jsonSchema<CreateBucketRequest>()
        }
        responses {
            HttpStatusCode.Created {
                description = "The new bucket"
                schema = jsonSchema<BucketResponse>()
            }
            errors(
                HttpStatusCode.BadRequest,
                "No valid bucket name or one reserved for the controller's sites, a domain that is not valid or one the " +
                    "controller serves or redirects, or a malformed request",
            )
            errors(
                HttpStatusCode.Conflict,
                "A bucket of the name exists, <name>.<root_domain> is already served, or another bucket is served at one of the domains",
            )
        }
    }

    delete("/{bucket}") {
        when (val result = withContext(Dispatchers.IO) { bucketService.delete(call.parameters["bucket"].orEmpty()) }) {
            BucketDeleteResult.Deleted -> call.respond(HttpStatusCode.NoContent)
            BucketDeleteResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            is BucketDeleteResult.Redirected ->
                call.respondErrors(
                    Text(HostingTexts.REDIRECTED, "sources" to result.sources.joinToString(", ")),
                    status = HttpStatusCode.Conflict,
                )
        }
    }.describe {
        tag(S3_TAG)
        summary = "Delete a bucket"
        description =
            "The bucket is no longer served and its key pair is refused. Its name stays taken and its objects stay " +
            "in the storage until it is purged. A bucket that redirects lead to is not deleted."
        parameters {
            bucketName()
        }
        responses {
            HttpStatusCode.NoContent { description = "The bucket is deleted" }
            errors(HttpStatusCode.NotFound, "Unknown bucket")
            errors(HttpStatusCode.Conflict, "Redirects lead to the bucket, named in the message")
        }
    }

    put("/{bucket}/domains") {
        val request = call.receive<UpdateDomainsRequest>()
        val name = call.parameters["bucket"].orEmpty()
        when (val result = withContext(Dispatchers.IO) { bucketService.updateDomains(name, request.domains, call.owner) }) {
            is BucketDomainsUpdateResult.Updated -> call.respond(result.bucket.response())
            BucketDomainsUpdateResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            is BucketDomainsUpdateResult.Invalid -> call.respondErrors(result.errors)
            BucketDomainsUpdateResult.DomainTaken -> call.respondErrors(HostingTexts.DOMAIN_TAKEN, status = HttpStatusCode.Conflict)
        }
    }.describe {
        tag(S3_TAG)
        summary = "Change the domains of a bucket"
        description = "Serves the bucket at the domains in their order instead of its current ones, none for no domain."
        parameters {
            bucketName()
        }
        requestBody {
            required = true
            schema = jsonSchema<UpdateDomainsRequest>()
        }
        responses {
            HttpStatusCode.OK {
                description = "The bucket with its new domains"
                schema = jsonSchema<BucketResponse>()
            }
            errors(HttpStatusCode.BadRequest, "A domain that is not valid or one the controller serves or redirects, or a malformed request")
            errors(HttpStatusCode.NotFound, "Unknown bucket")
            errors(HttpStatusCode.Conflict, "Another bucket is served at one of the domains")
        }
    }

    get("/{bucket}") {
        val bucket = withContext(Dispatchers.IO) { bucketService.byName(call.parameters["bucket"].orEmpty()) }
        if (bucket == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respond(bucket.response())
    }.describe {
        tag(S3_TAG)
        summary = "A bucket with the number of its objects and its size"
        parameters {
            bucketName()
        }
        responses {
            HttpStatusCode.OK {
                description = "The bucket, the domains it is served at and its statistics"
                schema = jsonSchema<BucketResponse>()
            }
            errors(HttpStatusCode.NotFound, "Unknown bucket")
        }
    }

    get("/{bucket}/secret") {
        val keyPair = withContext(Dispatchers.IO) { bucketService.keyPair(call.parameters["bucket"].orEmpty()) }
        if (keyPair == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(BucketSecretResponse(keyPair.first, keyPair.second.value))
    }.describe {
        tag(S3_TAG)
        summary = "The key pair of a bucket"
        parameters {
            bucketName()
        }
        responses {
            HttpStatusCode.OK {
                description = "The access key and the secret that may access the bucket"
                schema = jsonSchema<BucketSecretResponse>()
            }
            errors(HttpStatusCode.NotFound, "Unknown bucket")
        }
    }

    post("/{bucket}/secret") {
        when (val result = withContext(Dispatchers.IO) { bucketService.replaceKeyPair(call.parameters["bucket"].orEmpty()) }) {
            is BucketKeyPairReplaceResult.Replaced -> {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(BucketSecretResponse(result.accessKeyId, result.secretAccessKey.value))
            }
            BucketKeyPairReplaceResult.NotFound -> call.respond(HttpStatusCode.NotFound)
        }
    }.describe {
        tag(S3_TAG)
        summary = "Replace the key pair of a bucket"
        description = "A new access key and secret; the ones before stop working at once, e.g. after they leaked."
        parameters {
            bucketName()
        }
        responses {
            HttpStatusCode.OK {
                description = "The new access key and secret"
                schema = jsonSchema<BucketSecretResponse>()
            }
            errors(HttpStatusCode.NotFound, "Unknown bucket")
        }
    }
}

private suspend fun S3BucketService.statisticsResponse(bucket: String): BucketStatisticsResponse =
    try {
        statistics(bucket)
            ?.let { BucketStatisticsResponse(computing = false, objects = it.objects, bytes = it.bytes, computedAt = it.computedAt.apiTime()) }
            ?: BucketStatisticsResponse(computing = true)
    } catch (e: Exception) {
        logger.warn(logCause(e)) { "could not count the objects of bucket '$bucket'" }
        BucketStatisticsResponse(computing = false, failed = true)
    }

private fun io.ktor.openapi.Parameters.Builder.bucketName() =
    path("bucket") {
        description = "The name of the bucket"
        required = true
        schema = jsonSchema<String>()
    }
