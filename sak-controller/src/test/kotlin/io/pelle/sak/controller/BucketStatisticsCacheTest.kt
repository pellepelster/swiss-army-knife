package io.pelle.sak.controller

import com.github.benmanes.caffeine.cache.Ticker
import io.kotest.matchers.shouldBe
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import io.pelle.sak.s3.storage.BlobPath
import io.pelle.sak.s3.storage.FilesystemBinaryStorage
import io.pelle.sak.s3.storage.ObjectMetadata
import io.pelle.sak.s3.storage.PartRef
import io.pelle.sak.s3.storage.S3StorageBackend
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

class BucketStatisticsCacheTest {
    @TempDir
    lateinit var tempDir: Path

    private val storage by lazy { BinaryStorageS3Backend(FilesystemBinaryStorage(tempDir)) { bucket -> BlobPath(listOf("buckets", bucket)) } }

    private val nanos = AtomicLong()

    private val ticker = Ticker { nanos.get() }

    private val clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC)

    // runs what the cache hands it at once
    private val direct = Executor { it.run() }

    private fun put(key: String, content: String) = storage.putObject("bucket1", key, content.byteInputStream(), ObjectMetadata())

    private fun advance(duration: Duration) = nanos.addAndGet(duration.toNanos())

    @Test
    fun `counts a bucket once and serves the numbers until they are dropped`() {
        val statistics = BucketStatisticsCache(storage, clock, ticker, direct)
        put("a.txt", "12345")

        runBlocking { statistics.get("bucket1") } shouldBe BucketStatistics(1, 5, clock.instant())

        put("b.txt", "123")
        runBlocking { statistics.get("bucket1") } shouldBe BucketStatistics(1, 5, clock.instant())

        statistics.invalidate("bucket1")
        runBlocking { statistics.get("bucket1") } shouldBe BucketStatistics(2, 8, clock.instant())
    }

    @Test
    fun `recounts after five minutes and serves the old numbers meanwhile`() {
        val pending = mutableListOf<Runnable>()
        var deferred = false
        val executor = Executor { if (deferred) pending.add(it) else it.run() }
        val statistics = BucketStatisticsCache(storage, clock, ticker, executor)
        put("a.txt", "12345")
        runBlocking { statistics.get("bucket1") }?.objects shouldBe 1

        put("b.txt", "123")
        advance(BucketStatisticsCache.REFRESH_AFTER.plusSeconds(1))
        deferred = true

        // the recount is under way, the old numbers are served
        runBlocking { statistics.get("bucket1") }?.objects shouldBe 1

        pending.toList().forEach { it.run() }
        runBlocking { statistics.get("bucket1") }?.objects shouldBe 2
    }

    @Test
    fun `answers nothing while a bucket is counted for the first time`() {
        val pending = mutableListOf<Runnable>()
        val statistics = BucketStatisticsCache(storage, clock, ticker) { pending.add(it) }

        runBlocking { statistics.get("bucket1") } shouldBe null

        pending.toList().forEach { it.run() }
        runBlocking { statistics.get("bucket1") } shouldBe BucketStatistics(0, 0, clock.instant())
    }

    @Test
    fun `writes to a bucket drop its numbers`() {
        val changed = mutableListOf<String>()
        val backend: S3StorageBackend = InvalidatingS3StorageBackend(storage) { changed.add(it) }

        backend.putObject("bucket1", "a.txt", "1".byteInputStream(), ObjectMetadata())
        backend.deleteObject("bucket1", "a.txt")
        val upload = backend.createMultipartUpload("bucket1", "b.txt", ObjectMetadata())
        val etag = backend.uploadPart("bucket1", "b.txt", upload, 1, "part".byteInputStream())
        backend.completeMultipartUpload("bucket1", "b.txt", upload, listOf(PartRef(1, etag)))
        backend.headObject("bucket1", "b.txt")

        changed shouldBe listOf("bucket1", "bucket1", "bucket1")
    }
}
