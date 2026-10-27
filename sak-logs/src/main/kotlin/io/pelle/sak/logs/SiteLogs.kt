package io.pelle.sak.logs

import kotlinx.serialization.Serializable
import java.time.Duration

// one request of the ingress access log with the domain it was made to, as the access logs of a
// bucket list it
@Serializable
data class LogEntry(
    val time: String,
    val host: String,
    val path: String,
    val status: Int?,
)

// the access log entries of one site: searched in the stored entries, or followed as they arrive
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
            .map { it.toLogEntry() }

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

    // the entries of the last minutes first, then every new one; the tail has to be closed
    fun follow(site: Site): LogEntries {
        val tail = client.tail("${streamFilter(site)} | $FIELDS", FOLLOW_BACKLOG)
        return LogEntries(tail)
    }

    companion object {
        // the most entries a search answers
        const val SEARCH_LIMIT = 500

        // as far back as the longest statistics range, not over the whole retention
        const val SEARCH_WINDOW = "30d"

        // the entries a live view starts with
        val FOLLOW_BACKLOG: Duration = Duration.ofMinutes(5)

        private const val FIELDS = "fields _time, request.host, request.uri, status"

        private const val TOTAL = "total"

        // victorialogs evaluates regular expressions with go's re2, every metacharacter of the
        // text is escaped so it matches literally
        fun textFilter(text: String): String {
            val literal = text.trim().replace(Regex("""[\\.+*?()|\[\]{}^$]"""), """\\$0""")
            val pattern = quoted("(?i)$literal")
            return "(_msg:~$pattern OR user_agent:~$pattern)"
        }

        fun Map<String, String>.toLogEntry() =
            LogEntry(
                time = this["_time"].orEmpty(),
                host = this["request.host"].orEmpty(),
                path = this["request.uri"].orEmpty(),
                status = this["status"]?.toIntOrNull(),
            )
    }
}

class LogEntries(
    private val tail: LogTail,
) : java.io.Closeable {
    // blocks until the next entry arrives, null once the tail ended
    fun next(): LogEntry? = with(SiteLogs) { tail.next()?.toLogEntry() }

    override fun close() = tail.close()
}
