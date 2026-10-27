package io.pelle.sak.model

import org.jooq.impl.AbstractConverter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

// the timestamps are stored without a zone, in utc; jooq hands them to the repositories as instants
// (the forced type of every timestamp column, build.gradle.kts)
class InstantConverter : AbstractConverter<LocalDateTime, Instant>(LocalDateTime::class.java, Instant::class.java) {
    override fun from(stored: LocalDateTime?): Instant? = stored?.toInstant(ZoneOffset.UTC)

    override fun to(instant: Instant?): LocalDateTime? = instant?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
}
