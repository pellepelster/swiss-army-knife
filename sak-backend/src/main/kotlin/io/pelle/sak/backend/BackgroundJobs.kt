package io.pelle.sak.backend

import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.core.logCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.time.toKotlinDuration

private val logger = KotlinLogging.logger {}

// the work the backend does on its own, next to the requests: the configuration of caddy, the counts of
// the buckets and of the repositories of restic, the unfinished uploads. All of it runs here, the one
// way to run something in the background: coroutines of one scope, on daemon threads of their own
// named sak-background, that end with the backend (stop). A job that fails is logged and neither ends
// its next run nor another job
class BackgroundJobs(
    threads: Int = THREADS,
) : Executor {
    private val dispatcher =
        Executors.newFixedThreadPool(threads) { Thread(it, "sak-background").apply { isDaemon = true } }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("sak-background"))

    // runs the job once, soon; what names it in the log when it fails, e.g. count the repositories
    fun once(
        what: String,
        job: () -> Unit,
    ) {
        scope.launch { logged(what, job) }
    }

    // runs the job now and again every period after it ended, so two runs never overlap
    fun every(
        what: String,
        period: Duration,
        job: () -> Unit,
    ) {
        scope.launch {
            while (isActive) {
                logged(what, job)
                delay(period.toKotlinDuration())
            }
        }
    }

    // for what brings its own handling of a failure, e.g. a cache that loads its values here; in the
    // scope as well, so nothing of it starts after a stop
    override fun execute(command: Runnable) {
        scope.launch { command.run() }
    }

    // ends the jobs: one that waits for its next run ends at once, one that runs (e.g. the walk of a
    // count) ends its run, as a blocking call is not interrupted; nothing starts afterwards
    fun stop() {
        scope.cancel()
        dispatcher.close()
    }

    private fun logged(
        what: String,
        job: () -> Unit,
    ) {
        try {
            job()
        } catch (e: CancellationException) {
            // a stop is no failure, and must end the loop of the job
            throw e
        } catch (e: Exception) {
            logger.warn(logCause(e)) { "could not $what" }
        }
    }

    private companion object {
        // the counts walk files and may take long: room for them next to the short jobs
        const val THREADS = 4
    }
}
