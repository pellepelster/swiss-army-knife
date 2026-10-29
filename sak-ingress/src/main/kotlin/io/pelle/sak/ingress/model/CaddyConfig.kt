package io.pelle.sak.ingress.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CaddyConfig(
    val admin: CaddyAdmin? = null,
    val logging: CaddyLogging? = null,
    val apps: CaddyApps? = null,
)

@Serializable
data class CaddyAdmin(
    val listen: String? = null,
    val origins: List<String>? = null,
)

@Serializable
data class CaddyApps(
    val http: CaddyHttpApp? = null,
    val tls: CaddyTlsApp? = null,
)

@Serializable
data class CaddyTlsApp(
    val automation: CaddyTlsAutomation? = null,
)

@Serializable
data class CaddyTlsAutomation(
    val policies: List<CaddyTlsPolicy> = emptyList(),
)

@Serializable
data class CaddyTlsPolicy(
    val issuers: List<CaddyIssuer> = emptyList(),
)

@Serializable
data class CaddyIssuer(
    val module: String = "acme",
    val email: String? = null,
    // the directory of the acme ca, let's encrypt unless given
    val ca: String? = null,
)

@Serializable
data class CaddyHttpApp(
    val servers: Map<String, CaddyServer> = emptyMap(),
)

@Serializable
data class CaddyServer(
    val listen: List<String> = emptyList(),
    val routes: List<CaddyRoute> = emptyList(),
    @SerialName("automatic_https")
    val automaticHttps: CaddyAutomaticHttps? = null,
    val errors: CaddyErrorRoutes? = null,
    val logs: CaddyServerLogs? = null,
)

@Serializable
data class CaddyServerLogs(
    @SerialName("default_logger_name")
    val defaultLoggerName: String? = null,
    @SerialName("logger_names")
    val loggerNames: Map<String, List<String>>? = null,
    @SerialName("skip_hosts")
    val skipHosts: List<String>? = null,
    @SerialName("skip_unmapped_hosts")
    val skipUnmappedHosts: Boolean? = null,
)

@Serializable
data class CaddyErrorRoutes(
    val routes: List<CaddyRoute> = emptyList(),
)

@Serializable
data class CaddyAutomaticHttps(
    val disable: Boolean? = null,
)

@Serializable
data class CaddyRoute(
    val match: List<CaddyMatch>? = null,
    val handle: List<CaddyHandler> = emptyList(),
    val terminal: Boolean? = null,
)

@Serializable
data class CaddyMatch(
    val host: List<String>? = null,
    val path: List<String>? = null,
    val protocol: String? = null,
    val expression: String? = null,
)

@Serializable
sealed class CaddyHandler

@Serializable
@SerialName("log_append")
data class CaddyLogAppend(
    val key: String,
    val value: String,
) : CaddyHandler()

@Serializable
@SerialName("headers")
data class CaddyHeaders(
    val request: CaddyHeaderOps? = null,
    val response: CaddyHeaderOps? = null,
) : CaddyHandler()

@Serializable
data class CaddyHeaderOps(
    val set: Map<String, List<String>>? = null,
    val delete: List<String>? = null,
    val deferred: Boolean? = null,
)

@Serializable
@SerialName("static_response")
data class CaddyStaticResponse(
    @SerialName("status_code")
    val statusCode: Int? = null,
    val body: String? = null,
    val headers: Map<String, List<String>>? = null,
    val abort: Boolean? = null,
) : CaddyHandler()

@Serializable
@SerialName("error")
data class CaddyError(
    @SerialName("status_code")
    val statusCode: Int,
    val error: String? = null,
) : CaddyHandler()

@Serializable
@SerialName("file_server")
data class CaddyFileServer(
    val root: String? = null,
    @SerialName("index_names")
    val indexNames: List<String>? = null,
    @SerialName("status_code")
    val statusCode: Int? = null,
) : CaddyHandler()

@Serializable
@SerialName("reverse_proxy")
data class CaddyReverseProxy(
    val upstreams: List<CaddyUpstream> = emptyList(),
    @SerialName("health_checks")
    val healthChecks: CaddyHealthChecks? = null,
) : CaddyHandler()

@Serializable
data class CaddyHealthChecks(
    val active: CaddyActiveHealthCheck? = null,
)

@Serializable
data class CaddyActiveHealthCheck(
    val uri: String? = null,
    val port: Int? = null,
    val interval: String? = null,
    val timeout: String? = null,
    @SerialName("expect_status")
    val expectStatus: Int? = null,
)

@Serializable
data class CaddyUpstream(
    val dial: String,
)

@Serializable
@SerialName("request_body")
data class CaddyRequestBody(
    @SerialName("max_size")
    val maxSize: Long,
) : CaddyHandler()

@Serializable
@SerialName("rewrite")
data class CaddyRewrite(
    val uri: String? = null,
    @SerialName("strip_path_prefix")
    val stripPathPrefix: String? = null,
) : CaddyHandler()

@Serializable
@SerialName("subroute")
data class CaddySubroute(
    val routes: List<CaddyRoute> = emptyList(),
) : CaddyHandler()

@Serializable
data class CaddyLogging(
    val logs: Map<String, CaddyLog> = emptyMap(),
)

@Serializable
data class CaddyLog(
    val writer: CaddyLogWriter? = null,
    val encoder: CaddyLogEncoder? = null,
    val level: String? = null,
    val include: List<String>? = null,
    val exclude: List<String>? = null,
)

@Serializable
data class CaddyLogWriter(
    val output: String,
    val filename: String? = null,
    val roll: Boolean? = null,
    @SerialName("roll_size_mb")
    val rollSizeMb: Int? = null,
    @SerialName("roll_keep")
    val rollKeep: Int? = null,
    @SerialName("roll_keep_days")
    val rollKeepDays: Int? = null,
)

@Serializable
data class CaddyLogEncoder(
    val format: String,
    val wrap: CaddyLogEncoder? = null,
    val fields: Map<String, CaddyLogFieldFilter>? = null,
)

@Serializable
data class CaddyLogFieldFilter(
    val filter: String,
    @SerialName("ipv4_cidr")
    val ipv4Cidr: Int? = null,
    @SerialName("ipv6_cidr")
    val ipv6Cidr: Int? = null,
    val actions: List<CaddyLogFilterAction>? = null,
)

@Serializable
data class CaddyLogFilterAction(
    val type: String,
    val parameter: String? = null,
    val value: String? = null,
)
