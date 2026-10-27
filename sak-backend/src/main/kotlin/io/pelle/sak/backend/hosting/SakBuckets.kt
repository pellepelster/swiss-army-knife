package io.pelle.sak.backend.hosting

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
import io.pelle.sak.backend.hosting.CreateBucketResult
import io.pelle.sak.model.s3.S3Bucket
import io.pelle.sak.backend.hosting.S3BucketService
import io.pelle.sak.backend.hosting.UpdateDomainsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

// why a bucket could not be created or its domains not changed
private const val INVALID_NAME =
    "The name must have 3 to 63 lower case letters, digits and hyphens, start and end with a letter or digit, " +
        "and must not be reserved for the apps or the api."
private const val NAME_TAKEN = "A bucket of the name exists."
private const val SUBDOMAIN_TAKEN =
    "The subdomain of the name is already a domain of another bucket, a redirect, the apps or the api."
private const val INVALID_DOMAIN = "Every domain must be valid and neither one of the apps or the api nor a redirected one."
private const val DOMAIN_TAKEN = "Another bucket is served at one of the domains."

private const val S3_TAG = "s3"

// the buckets with their domains and statistics, meant to be mounted behind the
// authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.buckets(
    bucketService: S3BucketService,
    statistics: BucketStatisticsCache,
) {
    installApi()

    suspend fun S3Bucket.response() =
        BucketResponse(
            name,
            domains,
            bucketService.subdomain(name),
            createdAt.toString(),
            updatedAt?.toString(),
            statistics.response(name),
        )

    get {
        val page = call.pageRequest() ?: return@get
        // the buckets of the page are counted at once, one not counted within a moment is still being counted
        val buckets = page.of(withContext(Dispatchers.IO) { bucketService.all() })
        val responses = coroutineScope { buckets.items.map { bucket -> async { bucket.response() } }.awaitAll() }
        call.respond(page.of(responses, buckets.pagination.total))
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
        when (val result = withContext(Dispatchers.IO) { bucketService.create(request.name, request.domains) }) {
            is CreateBucketResult.Created -> {
                statistics.warm(listOf(result.bucket.name))
                call.respond(HttpStatusCode.Created, result.bucket.response())
            }
            CreateBucketResult.InvalidName -> call.respondErrors(INVALID_NAME)
            CreateBucketResult.InvalidDomain -> call.respondErrors(INVALID_DOMAIN)
            CreateBucketResult.NameTaken -> call.respondErrors(NAME_TAKEN, status = HttpStatusCode.Conflict)
            CreateBucketResult.SubdomainTaken -> call.respondErrors(SUBDOMAIN_TAKEN, status = HttpStatusCode.Conflict)
            CreateBucketResult.DomainTaken -> call.respondErrors(DOMAIN_TAKEN, status = HttpStatusCode.Conflict)
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
        when (withContext(Dispatchers.IO) { bucketService.delete(call.parameters["bucket"].orEmpty()) }) {
            DeleteBucketResult.Deleted -> call.respond(HttpStatusCode.NoContent)
            DeleteBucketResult.NotFound -> call.respond(HttpStatusCode.NotFound)
        }
    }.describe {
        tag(S3_TAG)
        summary = "Delete a bucket"
        description =
            "The bucket is no longer served and its key pair is refused. Its name stays taken and its objects stay " +
            "in the storage until it is purged."
        parameters {
            path("bucket") {
                description = "The name of the bucket"
                required = true
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.NoContent { description = "The bucket is deleted" }
            HttpStatusCode.NotFound { description = "Unknown bucket" }
        }
    }

    put("/{bucket}/domains") {
        val request = call.receive<UpdateDomainsRequest>()
        val name = call.parameters["bucket"].orEmpty()
        when (val result = withContext(Dispatchers.IO) { bucketService.updateDomains(name, request.domains) }) {
            is UpdateDomainsResult.Updated -> call.respond(result.bucket.response())
            UpdateDomainsResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            UpdateDomainsResult.InvalidDomain -> call.respondErrors(INVALID_DOMAIN)
            UpdateDomainsResult.DomainTaken -> call.respondErrors(DOMAIN_TAKEN, status = HttpStatusCode.Conflict)
        }
    }.describe {
        tag(S3_TAG)
        summary = "Change the domains of a bucket"
        description = "Serves the bucket at the domains in their order instead of its current ones, none for no domain."
        parameters {
            path("bucket") {
                description = "The name of the bucket"
                required = true
                schema = jsonSchema<String>()
            }
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
            HttpStatusCode.NotFound { description = "Unknown bucket" }
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
            path("bucket") {
                description = "The name of the bucket"
                required = true
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "The bucket, the domains it is served at and its statistics"
                schema = jsonSchema<BucketResponse>()
            }
            HttpStatusCode.NotFound { description = "Unknown bucket" }
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
            path("bucket") {
                description = "The name of the bucket"
                required = true
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "The access key and the secret that may access the bucket"
                schema = jsonSchema<BucketSecretResponse>()
            }
            HttpStatusCode.NotFound { description = "Unknown bucket" }
        }
    }
}

private suspend fun BucketStatisticsCache.response(bucket: String): BucketStatisticsResponse =
    try {
        get(bucket)
            ?.let { BucketStatisticsResponse(computing = false, objects = it.objects, bytes = it.bytes, computedAt = it.computedAt.toString()) }
            ?: BucketStatisticsResponse(computing = true)
    } catch (e: Exception) {
        logger.warn(e) { "could not count the objects of bucket '$bucket'" }
        BucketStatisticsResponse(computing = false, failed = true)
    }
