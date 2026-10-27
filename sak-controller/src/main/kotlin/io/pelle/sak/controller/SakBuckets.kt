package io.pelle.sak.controller

import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.model.s3.CreateBucketResult
import io.pelle.sak.model.s3.S3Bucket
import io.pelle.sak.model.s3.S3BucketService
import io.pelle.sak.model.s3.UpdateDomainsResult
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import io.pelle.sak.s3.storage.ObjectMetadata
import io.pelle.sak.s3.storage.PartRef
import io.pelle.sak.s3.storage.PutResult
import io.pelle.sak.s3.storage.S3StorageBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

// without the bucket's keys, they are served on their own
@Serializable
data class BucketResponse(
    val name: String,
    val domains: List<String>,
    val createdAt: String,
    val statistics: BucketStatisticsResponse,
)

@Serializable
data class CreateBucketRequest(
    val name: String,
    val domains: List<String> = emptyList(),
)

@Serializable
data class UpdateDomainsRequest(
    val domains: List<String>,
)

// why a bucket could not be created or changed
@Serializable
data class BucketErrorResponse(
    val error: String,
) {
    companion object {
        val INVALID_NAME = BucketErrorResponse("invalid-name")
        val NAME_TAKEN = BucketErrorResponse("name-taken")
        val INVALID_DOMAIN = BucketErrorResponse("invalid-domain")
        val DOMAIN_TAKEN = BucketErrorResponse("domain-taken")
    }
}

@Serializable
data class BucketSecretResponse(
    val accessKeyId: String,
    val secretAccessKey: String,
)

// objects, bytes and computedAt are missing while the bucket is still being counted or when it
// could not be counted
@Serializable
data class BucketStatisticsResponse(
    val computing: Boolean,
    val failed: Boolean = false,
    val objects: Long? = null,
    val bytes: Long? = null,
    val computedAt: String? = null,
)

data class BucketStatistics(
    val objects: Long,
    val bytes: Long,
    val computedAt: Instant,
)

// counting a bucket walks all of its objects, so the numbers are kept and recounted in the
// background after a while, the old ones are served meanwhile; a write drops them
class BucketStatisticsCache(
    private val storage: BinaryStorageS3Backend,
    clock: Clock = Clock.systemUTC(),
    ticker: Ticker = Ticker.systemTicker(),
    // the counting is blocking io
    executor: Executor = Executors.newFixedThreadPool(2) { Thread(it, "bucket-statistics").apply { isDaemon = true } },
) {
    private val cache: AsyncLoadingCache<String, BucketStatistics> =
        Caffeine
            .newBuilder()
            .ticker(ticker)
            .executor(executor)
            .refreshAfterWrite(REFRESH_AFTER)
            .expireAfterWrite(EXPIRE_AFTER)
            .buildAsync { bucket ->
                val usage = storage.usage(bucket)
                BucketStatistics(usage.blobs, usage.bytes, clock.instant())
            }

    // waits a moment for a bucket not counted yet, null when it takes longer; the copy is awaited,
    // giving up on it must not cancel the count itself
    suspend fun get(bucket: String): BucketStatistics? =
        withTimeoutOrNull(WAIT_FOR_COUNT) { cache.get(bucket).copy().await() }

    fun invalidate(bucket: String) = cache.synchronous().invalidate(bucket)

    // counts the buckets in the background, so the first look at them does not wait
    fun warm(buckets: Collection<String>) {
        buckets.forEach { bucket ->
            cache.get(bucket).exceptionally { e ->
                logger.warn(e) { "could not count the objects of bucket '$bucket'" }
                null
            }
        }
    }

    companion object {
        val REFRESH_AFTER: Duration = Duration.ofMinutes(5)

        val EXPIRE_AFTER: Duration = Duration.ofHours(1)

        private val WAIT_FOR_COUNT = 500.milliseconds
    }
}

// the s3 backend that drops the statistics of a bucket on every write to it
class InvalidatingS3StorageBackend(
    private val delegate: S3StorageBackend,
    private val changed: (bucket: String) -> Unit,
) : S3StorageBackend by delegate {
    override fun putObject(
        bucket: String,
        key: String,
        content: InputStream,
        metadata: ObjectMetadata,
    ): PutResult =
        try {
            delegate.putObject(bucket, key, content, metadata)
        } finally {
            changed(bucket)
        }

    override fun deleteObject(
        bucket: String,
        key: String,
    ) = try {
        delegate.deleteObject(bucket, key)
    } finally {
        changed(bucket)
    }

    override fun completeMultipartUpload(
        bucket: String,
        key: String,
        uploadId: String,
        parts: List<PartRef>,
    ): PutResult =
        try {
            delegate.completeMultipartUpload(bucket, key, uploadId, parts)
        } finally {
            changed(bucket)
        }
}

private const val S3_TAG = "s3"

// the buckets with their domains and statistics, meant to be mounted behind the console's
// authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.buckets(
    bucketService: S3BucketService,
    statistics: BucketStatisticsCache,
) {
    install(ContentNegotiation) { json() }

    suspend fun S3Bucket.response() =
        BucketResponse(
            name,
            domains,
            createdAt.toString(),
            statistics.response(name),
        )

    get {
        // counted at once, a bucket not counted within a moment is still being counted
        val responses =
            coroutineScope {
                withContext(Dispatchers.IO) { bucketService.all() }
                    .map { bucket -> async { bucket.response() } }
                    .awaitAll()
            }
        call.respond(responses)
    }.describe {
        tag(S3_TAG)
        summary = "The buckets with the number of their objects and their size"
        description =
            "The numbers are counted in the background and kept, recounted after ${BucketStatisticsCache.REFRESH_AFTER.toMinutes()} minutes and after every write to a " +
            "bucket; a bucket not counted yet is still computing, ask again."
        responses {
            HttpStatusCode.OK {
                description = "The buckets, the domains they are served at and their statistics"
                schema = jsonSchema<List<BucketResponse>>()
            }
        }
    }

    post {
        val request = call.receive<CreateBucketRequest>()
        when (val result = withContext(Dispatchers.IO) { bucketService.create(request.name, request.domains) }) {
            is CreateBucketResult.Created -> {
                statistics.warm(listOf(result.bucket.name))
                call.respond(HttpStatusCode.Created, result.bucket.response())
            }
            CreateBucketResult.InvalidName -> call.respond(HttpStatusCode.BadRequest, BucketErrorResponse.INVALID_NAME)
            CreateBucketResult.InvalidDomain -> call.respond(HttpStatusCode.BadRequest, BucketErrorResponse.INVALID_DOMAIN)
            CreateBucketResult.NameTaken -> call.respond(HttpStatusCode.Conflict, BucketErrorResponse.NAME_TAKEN)
            CreateBucketResult.DomainTaken -> call.respond(HttpStatusCode.Conflict, BucketErrorResponse.DOMAIN_TAKEN)
        }
    }.describe {
        tag(S3_TAG)
        summary = "Create a bucket"
        description =
            "Creates the bucket with a key pair that may access this bucket only, served at the domains in their order. " +
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
            HttpStatusCode.BadRequest {
                description =
                    "invalid-name: no valid bucket name, or one reserved for the controller's sites; invalid-domain: no valid " +
                    "domain, or one the controller serves or redirects"
                schema = jsonSchema<BucketErrorResponse>()
            }
            HttpStatusCode.Conflict {
                description = "name-taken: a bucket of the name exists; domain-taken: another bucket is served at a domain"
                schema = jsonSchema<BucketErrorResponse>()
            }
        }
    }

    put("/{bucket}/domains") {
        val request = call.receive<UpdateDomainsRequest>()
        val name = call.parameters["bucket"].orEmpty()
        when (val result = withContext(Dispatchers.IO) { bucketService.updateDomains(name, request.domains) }) {
            is UpdateDomainsResult.Updated -> call.respond(result.bucket.response())
            UpdateDomainsResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            UpdateDomainsResult.InvalidDomain -> call.respond(HttpStatusCode.BadRequest, BucketErrorResponse.INVALID_DOMAIN)
            UpdateDomainsResult.DomainTaken -> call.respond(HttpStatusCode.Conflict, BucketErrorResponse.DOMAIN_TAKEN)
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
            HttpStatusCode.BadRequest {
                description = "invalid-domain: no valid domain, or one the controller serves or redirects"
                schema = jsonSchema<BucketErrorResponse>()
            }
            HttpStatusCode.NotFound { description = "Unknown bucket" }
            HttpStatusCode.Conflict {
                description = "domain-taken: another bucket is served at a domain"
                schema = jsonSchema<BucketErrorResponse>()
            }
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
        val bucket = withContext(Dispatchers.IO) { bucketService.byName(call.parameters["bucket"].orEmpty()) }
        if (bucket == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(BucketSecretResponse(bucket.accessKeyId, bucket.secretAccessKey))
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
