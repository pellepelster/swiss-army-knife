package io.pelle.sak.logs

import java.time.Duration
import java.time.Instant

// the time ranges the access logs offer, the longest one has to fit into the logs retention
enum class MetricsRange(
    val key: String,
    val label: String,
    val step: Duration,
    val buckets: Int,
) {
    LAST_15_MINUTES("15m", "15 minutes", Duration.ofSeconds(30), 30),
    LAST_HOUR("1h", "1 hour", Duration.ofMinutes(2), 30),
    LAST_4_HOURS("4h", "4 hours", Duration.ofMinutes(10), 24),
    LAST_6_HOURS("6h", "6 hours", Duration.ofMinutes(15), 24),
    LAST_12_HOURS("12h", "12 hours", Duration.ofMinutes(30), 24),
    LAST_24_HOURS("24h", "24 hours", Duration.ofHours(1), 24),
    LAST_7_DAYS("7d", "7 days", Duration.ofHours(6), 28),
    LAST_30_DAYS("30d", "30 days", Duration.ofDays(1), 30),
    ;

    companion object {
        val DEFAULT = LAST_24_HOURS

        fun from(key: String?) = if (key == null) DEFAULT else entries.firstOrNull { it.key == key }
    }
}

// the buckets of a range, aligned to its step so consecutive queries share their buckets; the
// last one is the bucket now falls into
class TimeSeriesGrid(
    val range: MetricsRange,
    now: Instant,
) {
    private val stepSeconds = range.step.seconds

    val start: Instant
    val end: Instant
    val bucketStarts: List<Instant>

    init {
        val lastBucket = Instant.ofEpochSecond(now.epochSecond / stepSeconds * stepSeconds)
        start = lastBucket.minusSeconds(stepSeconds * (range.buckets - 1))
        end = lastBucket.plusSeconds(stepSeconds)
        bucketStarts = (0 until range.buckets).map { start.plusSeconds(stepSeconds * it) }
    }

    fun emptyValues() = LongArray(range.buckets)

    fun add(
        target: LongArray,
        points: List<StatsPoint>,
    ) {
        points.forEach { point ->
            // floored, a point just before the start must not land in the first bucket
            val index = Math.floorDiv(point.timestamp.epochSecond - start.epochSecond, stepSeconds).toInt()
            if (index in 0 until range.buckets) target[index] += point.value.toLong()
        }
    }
}
