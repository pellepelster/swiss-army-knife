package io.pelle.sak.logs

import kotlinx.serialization.Serializable
import java.time.Duration

// one request of the ingress access log, as the console's live view lists it
@Serializable
data class LogEntry(
    val time: String,
    val site: String,
    val path: String,
    val status: Int?,
)

// the access log entries of one site or all of them: searched in the stored entries, or
// followed as they arrive
class SiteLogs(
    private val client: VictoriaLogsClient,
) {
    // the newest entries whose request line (method, host, path, status) or user agent contains
    // the text, case-insensitively
    fun search(
        site: Site?,
        text: String,
        limit: Int = SEARCH_LIMIT,
    ): List<LogEntry> =
        client
            .query("${streamFilter(site)} _time:$SEARCH_WINDOW ${textFilter(text)} | $FIELDS | sort by (_time desc) | limit $limit")
            .map { it.toLogEntry() }

    // the entries of the last minutes first, then every new one; the tail has to be closed
    fun follow(site: Site?): LogEntries {
        val tail = client.tail("${streamFilter(site)} | $FIELDS", FOLLOW_BACKLOG)
        return LogEntries(tail)
    }

    companion object {
        // as many as the console's live view keeps of the followed ones
        const val SEARCH_LIMIT = 500

        // as far back as the longest statistics range, not over the whole retention
        const val SEARCH_WINDOW = "30d"

        // the entries a live view starts with
        val FOLLOW_BACKLOG: Duration = Duration.ofMinutes(5)

        private const val FIELDS = "fields _time, site, request.uri, status"

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
                site = this[SiteMetricsService.SITE].orEmpty(),
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
