package io.pelle.sak.backend

import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.api.logCause
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

// the work the backend does on its own, next to the requests: the configuration of caddy, the counts of
// the buckets and of the repositories of restic, the unfinished uploads. All of it runs here, the one
// way to run something in the background: on threads that end with the backend (stop), a job that
// fails is logged and neither ends its next run nor another job
class BackgroundJobs(
    threads: Int = THREADS,
) : Executor {
    private val executor = Executors.newScheduledThreadPool(threads) { Thread(it, "sak-background").apply { isDaemon = true } }

    // runs the job once, soon; what names it in the log when it fails, e.g. count the repositories
    fun once(
        what: String,
        job: () -> Unit,
    ) {
        executor.execute(logged(what, job))
    }

    // runs the job now and again every period after it ended
    fun every(
        what: String,
        period: Duration,
        job: () -> Unit,
    ) {
        executor.scheduleWithFixedDelay(logged(what, job), 0, period.toMillis(), TimeUnit.MILLISECONDS)
    }

    // for what brings its own handling of a failure, e.g. a cache that loads its values here
    override fun execute(command: Runnable) = executor.execute(command)

    // ends the jobs, the ones that run are interrupted; nothing runs afterwards
    fun stop() {
        executor.shutdownNow()
    }

    private fun logged(
        what: String,
        job: () -> Unit,
    ) = Runnable {
        try {
            job()
        } catch (e: Exception) {
            logger.warn(logCause(e)) { "could not $what" }
        }
    }

    private companion object {
        // the counts walk files and may take long: room for them next to the short jobs
        const val THREADS = 4
    }
}
