package io.pelle.sak.controller

import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.config.SakConfig
import io.pelle.sak.model.s3.S3BucketService
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import io.pelle.sak.s3.storage.ObjectMetadata
import io.pelle.sak.s3.storage.PartRef
import io.pelle.sak.s3.storage.PutResult
import io.pelle.sak.s3.storage.S3StorageBackend
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

data class Bucket(
    val name: String,
    val domains: List<String>,
)

@Serializable
data class BucketResponse(
    val name: String,
    val domains: List<String>,
    val statistics: BucketStatisticsResponse,
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

// the buckets of the controller, with the domains the configuration serves them at
fun buckets(
    config: SakConfig,
    bucketService: S3BucketService,
): List<Bucket> = bucketService.all().map { Bucket(it.name, config.s3.buckets[it.name]?.domains.orEmpty()) }

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

// the buckets with their statistics, meant to be mounted behind the console's authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.buckets(
    buckets: () -> List<Bucket>,
    statistics: BucketStatisticsCache,
) {
    install(ContentNegotiation) { json() }

    get {
        // counted at once, a bucket not counted within a moment is still being counted
        val responses =
            coroutineScope {
                buckets()
                    .map { bucket -> async { BucketResponse(bucket.name, bucket.domains, statistics.response(bucket.name)) } }
                    .awaitAll()
            }
        call.respond(responses)
    }.describe {
        tag(S3_TAG)
        summary = "The buckets with the number of their objects and their size"
        description =
            "The numbers are counted in the background and kept, recounted after " +
            "${BucketStatisticsCache.REFRESH_AFTER.toMinutes()} minutes and after every write to a bucket; a bucket not " +
            "counted yet is still computing, ask again."
        responses {
            HttpStatusCode.OK {
                description = "The buckets, the domains they are served at and their statistics"
                schema = jsonSchema<List<BucketResponse>>()
            }
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
