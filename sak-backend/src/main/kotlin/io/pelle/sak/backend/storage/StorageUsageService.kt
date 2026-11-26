package io.pelle.sak.backend.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.core.storage.StorageUsage
import io.pelle.sak.core.storage.StorageUsageReporter
import io.pelle.sak.backend.BackgroundJobs
import io.pelle.sak.core.logCause
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

// what every storage of the instance keeps, each by its name (the files, the buckets, the repositories
// of restic), and their total. Counted when the server starts and every PERIOD after, in the background;
// an ask answers the last count and counts nothing. A storage whose count fails keeps its last one
class StorageUsageService(
    private val reporters: Map<String, StorageUsageReporter>,
    private val jobs: BackgroundJobs,
    private val period: Duration = PERIOD,
) {
    private val usages = ConcurrentHashMap<String, StorageUsage>()

    // counts every storage now, then every period after the count ended
    fun start() = jobs.every("count what the storages keep", period) { count() }

    // counts every storage once, one after the other
    fun count() {
        reporters.forEach { (name, reporter) ->
            try {
                usages[name] = reporter.storageUsage()
            } catch (e: Exception) {
                logger.warn(logCause(e)) { "could not count what the storage '$name' keeps" }
            }
        }
    }

    // the last count of every storage counted so far, by its name
    fun usages(): Map<String, StorageUsage> = usages.toMap()

    // all the storages together: their bytes and their items added up, as of the oldest of their counts;
    // null before the first count
    fun total(): StorageUsage? {
        val all = usages.values.toList().takeIf { it.isNotEmpty() } ?: return null
        return StorageUsage(all.sumOf { it.bytes }, all.sumOf { it.items }, all.minOf { it.computedAt })
    }

    companion object {
        val PERIOD: Duration = Duration.ofMinutes(15)
    }
}
