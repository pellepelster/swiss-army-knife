package io.pelle.sak.backend.storage

import io.kotest.matchers.shouldBe
import io.pelle.sak.api.storage.StorageUsage
import io.pelle.sak.api.storage.StorageUsageReporter
import io.pelle.sak.backend.BackgroundJobs
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StorageUsageServiceTest {
    private val ten = Instant.parse("2026-11-24T10:00:00Z")

    // a storage that keeps bytes in items, counted at a time, and counts how often it was counted
    private class Reporter(
        var usage: StorageUsage?,
    ) : StorageUsageReporter {
        val counts = AtomicInteger()

        override fun storageUsage(): StorageUsage {
            counts.incrementAndGet()
            return usage ?: error("the storage can not be counted")
        }
    }

    @Test
    fun `the total adds up every storage as of the oldest count, an ask counts nothing`() {
        val files = Reporter(StorageUsage(24, 3, ten.plusSeconds(5)))
        val restic = Reporter(StorageUsage(1000, 10, ten))
        val service = StorageUsageService(mapOf("files" to files, "restic" to restic), BackgroundJobs(threads = 1))
        service.total() shouldBe null
        service.usages() shouldBe emptyMap()

        service.count()

        service.total() shouldBe StorageUsage(1024, 13, ten)
        service.usages().keys shouldBe setOf("files", "restic")
        service.total()
        service.usages()
        files.counts.get() shouldBe 1
    }

    @Test
    fun `a storage whose count fails keeps its last one, the others are counted`() {
        val files = Reporter(StorageUsage(24, 3, ten))
        val restic = Reporter(StorageUsage(1000, 10, ten))
        val service = StorageUsageService(mapOf("files" to files, "restic" to restic), BackgroundJobs(threads = 1))
        service.count()

        files.usage = null
        restic.usage = StorageUsage(2000, 20, ten.plusSeconds(60))
        service.count()

        service.usages() shouldBe mapOf("files" to StorageUsage(24, 3, ten), "restic" to StorageUsage(2000, 20, ten.plusSeconds(60)))
    }

    @Test
    fun `started, it counts at once and again every period`() {
        val counted = CountDownLatch(2)
        val reporter =
            StorageUsageReporter {
                counted.countDown()
                StorageUsage(1, 1, Instant.now())
            }
        val jobs = BackgroundJobs(threads = 1)
        try {
            StorageUsageService(mapOf("restic" to reporter), jobs, Duration.ofMillis(10)).start()
            counted.await(5, TimeUnit.SECONDS) shouldBe true
        } finally {
            jobs.stop()
        }
        StorageUsageService.PERIOD shouldBe Duration.ofMinutes(15)
    }
}
