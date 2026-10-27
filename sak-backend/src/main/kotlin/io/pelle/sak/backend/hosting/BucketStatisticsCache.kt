package io.pelle.sak.backend.hosting

import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

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
