package io.pelle.sak.ingress.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CaddyConfig(
    val apps: CaddyApps? = null,
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
    val logs: CaddyServerLogs? = null,
)

// present, the server writes the access log to the logger the caddyfile of the ingress configures
@Serializable
data object CaddyServerLogs

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
    val response: CaddyHeaderOps? = null,
) : CaddyHandler()

@Serializable
data class CaddyHeaderOps(
    val set: Map<String, List<String>>? = null,
    val deferred: Boolean? = null,
)

@Serializable
@SerialName("static_response")
data class CaddyStaticResponse(
    @SerialName("status_code")
    val statusCode: Int? = null,
    val headers: Map<String, List<String>>? = null,
    val abort: Boolean? = null,
) : CaddyHandler()

@Serializable
@SerialName("file_server")
data class CaddyFileServer(
    val root: String? = null,
    @SerialName("index_names")
    val indexNames: List<String>? = null,
) : CaddyHandler()

@Serializable
@SerialName("reverse_proxy")
data class CaddyReverseProxy(
    val upstreams: List<CaddyUpstream> = emptyList(),
) : CaddyHandler()

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
) : CaddyHandler()
