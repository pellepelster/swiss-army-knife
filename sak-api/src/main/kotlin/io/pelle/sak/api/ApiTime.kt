package io.pelle.sak.api

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// how the api writes a point in time: ISO 8601 in UTC with milliseconds, always the same length,
// e.g. 2026-10-29T08:00:00.000Z; what a browser's Date and every other client parse alike
private val API_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

fun Instant.apiTime(): String = API_TIME.format(this)
