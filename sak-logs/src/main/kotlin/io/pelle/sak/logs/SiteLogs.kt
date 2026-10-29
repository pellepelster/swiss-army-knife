package io.pelle.sak.logs

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException


// the access log entries of one site, searched in the stored entries
class SiteLogs(
    private val client: VictoriaLogsClient,
) {
    // the entries whose request line (method, host, path, status) or user agent contains the
    // text, case-insensitively, newest first, from offset on
    fun search(
        site: Site,
        text: String,
        offset: Long = 0,
        limit: Int = SEARCH_LIMIT,
    ): List<LogEntry> =
        client
            .query("${searchFilter(site, text)} | $FIELDS | sort by (_time desc) | offset $offset | limit $limit")
            .mapNotNull { it.toLogEntry() }

    // how many entries the search finds altogether
    fun count(
        site: Site,
        text: String,
    ): Long =
        client
            .query("${searchFilter(site, text)} | stats count() $TOTAL")
            .firstOrNull()
            ?.get(TOTAL)
            ?.toLongOrNull() ?: 0

    private fun searchFilter(
        site: Site,
        text: String,
    ) = "${streamFilter(site)} _time:$SEARCH_WINDOW ${textFilter(text)}"

    companion object {
        // the most entries a search answers
        const val SEARCH_LIMIT = 500

        // as far back as the longest statistics range, not over the whole retention
        const val SEARCH_WINDOW = "30d"

        private const val FIELDS = "fields _time, request.host, request.uri, status"

        private const val TOTAL = "total"

        // victorialogs evaluates regular expressions with go's re2, every metacharacter of the
        // text is escaped so it matches literally
        fun textFilter(text: String): String {
            val literal = text.trim().replace(Regex("""[\\.+*?()|\[\]{}^$]"""), """\\$0""")
            val pattern = quoted("(?i)$literal")
            return "(_msg:~$pattern OR user_agent:~$pattern)"
        }

        // none for an entry without a readable time, which the stored entries always have
        fun Map<String, String>.toLogEntry(): LogEntry? =
            LogEntry(
                time = this["_time"]?.let(::parseTime) ?: return null,
                host = this["request.host"].orEmpty(),
                path = this["request.uri"].orEmpty(),
                status = this["status"]?.toIntOrNull(),
            )
    }
}

// an rfc 3339 timestamp as victorialogs tells it, null when it is none
private fun parseTime(time: String): Instant? =
    try {
        OffsetDateTime.parse(time).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

// one request of the ingress access log with the domain it was made to, status 0 for a request
// caddy closed without an answer
data class LogEntry(
    val time: Instant,
    val host: String,
    val path: String,
    val status: Int?,
)
