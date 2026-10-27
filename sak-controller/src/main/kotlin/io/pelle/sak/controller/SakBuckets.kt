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
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.config.SakConfig
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import io.pelle.sak.s3.storage.ObjectMetadata
import io.pelle.sak.s3.storage.PartRef
import io.pelle.sak.s3.storage.PutResult
import io.pelle.sak.s3.storage.S3StorageBackend
import kotlinx.coroutines.future.await
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

@Serializable
data class BucketResponse(
    val name: String,
    val domains: List<String>,
)

// objects, bytes and computedAt are missing while the bucket is still being counted
@Serializable
data class BucketStatisticsResponse(
    val bucket: String,
    val computing: Boolean,
    val objects: Long? = null,
    val bytes: Long? = null,
    val computedAt: String? = null,
)

data class BucketStatistics(
    val objects: Long,
    val bytes: Long,
    val computedAt: Instant,
)

// the buckets of the controller; the one place they come from, so they can move from the
// configuration to the database
fun buckets(config: SakConfig): List<BucketResponse> =
    config.s3.buckets.map { (name, bucket) -> BucketResponse(name, bucket.domains) }

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

// the buckets and their statistics, meant to be mounted behind the console's authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.buckets(
    buckets: () -> List<BucketResponse>,
    statistics: BucketStatisticsCache,
) {
    install(ContentNegotiation) { json() }

    get {
        call.respond(buckets())
    }.describe {
        tag(S3_TAG)
        summary = "The buckets"
        responses {
            HttpStatusCode.OK {
                description = "The buckets and the domains they are served at"
                schema = jsonSchema<List<BucketResponse>>()
            }
        }
    }

    get("/{bucket}/statistics") {
        val bucket = buckets().firstOrNull { it.name == call.parameters["bucket"] }
        if (bucket == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val counted =
            try {
                statistics.get(bucket.name)
            } catch (e: Exception) {
                logger.warn(e) { "could not count the objects of bucket '${bucket.name}'" }
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
        if (counted == null) {
            call.response.header(HttpHeaders.RetryAfter, "3")
            call.respond(HttpStatusCode.Accepted, BucketStatisticsResponse(bucket.name, computing = true))
            return@get
        }
        call.respond(BucketStatisticsResponse(bucket.name, false, counted.objects, counted.bytes, counted.computedAt.toString()))
    }.describe {
        tag(S3_TAG)
        summary = "The number of objects of a bucket and their size"
        description =
            "Counted in the background and kept, recounted after ${BucketStatisticsCache.REFRESH_AFTER.toMinutes()} minutes " +
            "and after every write to the bucket."
        parameters {
            path("bucket") {
                description = "The name of the bucket"
                required = true
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "The statistics and when they were counted"
                schema = jsonSchema<BucketStatisticsResponse>()
            }
            HttpStatusCode.Accepted {
                description = "The bucket is still being counted, ask again"
                schema = jsonSchema<BucketStatisticsResponse>()
            }
            HttpStatusCode.NotFound { description = "Unknown bucket" }
            HttpStatusCode.ServiceUnavailable { description = "The bucket could not be counted" }
        }
    }
}
