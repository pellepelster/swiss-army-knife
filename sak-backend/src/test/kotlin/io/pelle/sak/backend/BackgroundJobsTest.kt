package io.pelle.sak.backend

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BackgroundJobsTest {
    @Test
    fun `a job that fails runs again and ends no other job, a stop ends them all`() {
        val jobs = BackgroundJobs(threads = 2)
        val runs = AtomicInteger()
        val threeRuns = CountDownLatch(3)
        val other = CountDownLatch(1)
        try {
            jobs.every("fail every time", Duration.ofMillis(5)) {
                runs.incrementAndGet()
                threeRuns.countDown()
                error("fails")
            }
            jobs.once("fail once") { error("fails") }
            jobs.once("run next to them") { other.countDown() }

            threeRuns.await(5, TimeUnit.SECONDS) shouldBe true
            other.await(5, TimeUnit.SECONDS) shouldBe true
        } finally {
            jobs.stop()
        }
        // nothing runs after the stop
        Thread.sleep(50)
        val stopped = runs.get()
        Thread.sleep(50)
        runs.get() shouldBe stopped
    }
}
