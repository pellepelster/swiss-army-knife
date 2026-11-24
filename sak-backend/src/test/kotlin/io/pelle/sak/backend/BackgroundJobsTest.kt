package io.pelle.sak.backend

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
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

    @Test
    fun `a recurring job runs at once, then waits its period after each run ended, on a thread named sak-background`() {
        val jobs = BackgroundJobs(threads = 2)
        val starts = ConcurrentLinkedQueue<Long>()
        val ends = ConcurrentLinkedQueue<Long>()
        val threads = ConcurrentLinkedQueue<String>()
        val twoRuns = CountDownLatch(2)
        val begun = System.nanoTime()
        try {
            jobs.every("run slowly", Duration.ofSeconds(1)) {
                starts.add(System.nanoTime())
                threads.add(Thread.currentThread().name)
                Thread.sleep(50)
                ends.add(System.nanoTime())
                twoRuns.countDown()
            }
            twoRuns.await(5, TimeUnit.SECONDS) shouldBe true
        } finally {
            jobs.stop()
        }
        // the first run does not wait for the period, however slow the start of its thread
        (TimeUnit.NANOSECONDS.toMillis(starts.first() - begun) < 1000) shouldBe true
        // the second starts the period after the first ended, not after it started
        (TimeUnit.NANOSECONDS.toMillis(starts.elementAt(1) - ends.first()) >= 1000) shouldBe true
        // with assertions on, as in the tests, kotlinx.coroutines appends the coroutine to the name
        threads.all { it.startsWith("sak-background") } shouldBe true
    }

    @Test
    fun `it is the executor of what brings its own handling, nothing starts after a stop`() {
        val jobs = BackgroundJobs(threads = 1)
        val ran = CountDownLatch(1)
        jobs.execute { ran.countDown() }
        ran.await(5, TimeUnit.SECONDS) shouldBe true

        jobs.stop()
        val after = AtomicInteger()
        jobs.execute { after.incrementAndGet() }
        jobs.once("run after the stop") { after.incrementAndGet() }
        jobs.every("run after the stop", Duration.ofMillis(1)) { after.incrementAndGet() }
        Thread.sleep(50)
        after.get() shouldBe 0
    }
}
