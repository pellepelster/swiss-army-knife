package io.pelle.sak.api

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// a point in time of the api: ISO 8601 in UTC with milliseconds, always the same length, e.g.
// 2026-10-29T08:00:00.000Z, which every client parses alike
private val API_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

fun Instant.apiTime(): String = API_TIME.format(this)
