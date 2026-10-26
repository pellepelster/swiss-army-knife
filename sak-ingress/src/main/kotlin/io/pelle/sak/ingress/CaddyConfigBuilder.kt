package io.pelle.sak.ingress

import io.pelle.sak.ingress.model.CaddyActiveHealthCheck
import io.pelle.sak.ingress.model.CaddyApps
import io.pelle.sak.ingress.model.CaddyAutomaticHttps
import io.pelle.sak.ingress.model.CaddyConfig
import io.pelle.sak.ingress.model.CaddyError
import io.pelle.sak.ingress.model.CaddyErrorRoutes
import io.pelle.sak.ingress.model.CaddyFileServer
import io.pelle.sak.ingress.model.CaddyHandler
import io.pelle.sak.ingress.model.CaddyHeaderOps
import io.pelle.sak.ingress.model.CaddyHeaders
import io.pelle.sak.ingress.model.CaddyHealthChecks
import io.pelle.sak.ingress.model.CaddyHttpApp
import io.pelle.sak.ingress.model.CaddyIssuer
import io.pelle.sak.ingress.model.CaddyLog
import io.pelle.sak.ingress.model.CaddyLogAppend
import io.pelle.sak.ingress.model.CaddyLogEncoder
import io.pelle.sak.ingress.model.CaddyLogFieldFilter
import io.pelle.sak.ingress.model.CaddyLogFilterAction
import io.pelle.sak.ingress.model.CaddyLogWriter
import io.pelle.sak.ingress.model.CaddyLogging
import io.pelle.sak.ingress.model.CaddyMatch
import io.pelle.sak.ingress.model.CaddyRequestBody
import io.pelle.sak.ingress.model.CaddyReverseProxy
import io.pelle.sak.ingress.model.CaddyRewrite
import io.pelle.sak.ingress.model.CaddyRoute
import io.pelle.sak.ingress.model.CaddyServer
import io.pelle.sak.ingress.model.CaddyServerLogs
import io.pelle.sak.ingress.model.CaddyStaticResponse
import io.pelle.sak.ingress.model.CaddyTlsApp
import io.pelle.sak.ingress.model.CaddyTlsAutomation
import io.pelle.sak.ingress.model.CaddyTlsPolicy
import io.pelle.sak.ingress.model.CaddyUpstream

fun caddyConfig(block: CaddyConfigBuilder.() -> Unit): CaddyConfig = CaddyConfigBuilder().apply(block).build()

@DslMarker
annotation class CaddyDsl

@CaddyDsl
class CaddyConfigBuilder {
    private val servers = linkedMapOf<String, CaddyServer>()
    private var acmeEmail: String? = null
    private var accessLog: CaddyLog? = null

    fun server(
        name: String,
        block: CaddyServerBuilder.() -> Unit,
    ) {
        servers[name] = CaddyServerBuilder().apply(block).build()
    }

    fun accessLog(block: CaddyAccessLogBuilder.() -> Unit) {
        accessLog = CaddyAccessLogBuilder().apply(block).build()
    }

    fun automaticTls(email: String) {
        acmeEmail = email
    }

    fun build(): CaddyConfig =
        CaddyConfig(
            logging =
                accessLog?.let {
                    CaddyLogging(
                        mapOf(
                            ACCESS_LOG to it,
                            DEFAULT_LOG to CaddyLog(exclude = listOf(ACCESS_LOGGER)),
                        ),
                    )
                },
            apps =
                CaddyApps(
                    http = CaddyHttpApp(servers.toMap()),
                    tls =
                        acmeEmail?.let {
                            CaddyTlsApp(CaddyTlsAutomation(listOf(CaddyTlsPolicy(listOf(CaddyIssuer(email = it))))))
                        },
                ),
        )

    companion object {
        const val ACCESS_LOGGER = "http.log.access"

        const val ACCESS_LOG = "access"

        const val DEFAULT_LOG = "default"
    }
}

@CaddyDsl
class CaddyAccessLogBuilder {
    private var writer: CaddyLogWriter? = null
    private val fields = linkedMapOf<String, CaddyLogFieldFilter>()

    fun file(
        filename: String,
        rollSizeMb: Int? = null,
        rollKeep: Int? = null,
        rollKeepDays: Int? = null,
    ) {
        writer =
            CaddyLogWriter(
                output = "file",
                filename = filename,
                roll = true,
                rollSizeMb = rollSizeMb,
                rollKeep = rollKeep,
                rollKeepDays = rollKeepDays,
            )
    }

    fun maskIpAddresses(
        ipv4Cidr: Int,
        ipv6Cidr: Int,
    ) {
        listOf("request>remote_ip", "request>client_ip").forEach {
            fields[it] = CaddyLogFieldFilter(filter = "ip_mask", ipv4Cidr = ipv4Cidr, ipv6Cidr = ipv6Cidr)
        }
    }

    fun deleteQueryParameters(vararg parameters: String) {
        fields["request>uri"] =
            CaddyLogFieldFilter(
                filter = "query",
                actions = parameters.map { CaddyLogFilterAction(type = "delete", parameter = it) },
            )
    }

    fun build(): CaddyLog {
        val json = CaddyLogEncoder(format = "json")
        return CaddyLog(
            writer = writer,
            encoder =
                if (fields.isEmpty()) {
                    json
                } else {
                    CaddyLogEncoder(format = "filter", wrap = json, fields = fields.toMap())
                },
            include = listOf(CaddyConfigBuilder.ACCESS_LOGGER),
        )
    }
}

@CaddyDsl
class CaddyServerBuilder {
    private val listen = mutableListOf<String>()
    private val routes = mutableListOf<CaddyRoute>()
    private var automaticHttps: CaddyAutomaticHttps? = null
    private var logs: CaddyServerLogs? = null

    fun disableAutomaticHttps() {
        automaticHttps = CaddyAutomaticHttps(disable = true)
    }

    fun enableAccessLog() {
        logs = CaddyServerLogs()
    }

    fun listen(vararg addresses: String) {
        listen.addAll(addresses)
    }

    fun route(block: CaddyRouteBuilder.() -> Unit) {
        routes.add(CaddyRouteBuilder().apply(block).build())
    }

    fun errors(block: CaddyErrorsBuilder.() -> Unit) {
        errorRoutes.addAll(CaddyErrorsBuilder().apply(block).build())
    }

    private val errorRoutes = mutableListOf<CaddyRoute>()

    fun build(): CaddyServer {
        require(listen.isNotEmpty()) { "a server must declare at least one listen address" }
        return CaddyServer(
            listen = listen.toList(),
            routes = routes.toList(),
            automaticHttps = automaticHttps,
            errors = errorRoutes.toList().ifEmpty { null }?.let { CaddyErrorRoutes(it) },
            logs = logs,
        )
    }
}

@CaddyDsl
class CaddyErrorsBuilder {
    private val routes = mutableListOf<CaddyRoute>()

    fun route(block: CaddyRouteBuilder.() -> Unit) {
        routes.add(CaddyRouteBuilder().apply(block).build())
    }

    fun build(): List<CaddyRoute> = routes.toList()
}

@CaddyDsl
class CaddyRouteBuilder {
    private val hosts = mutableListOf<String>()
    private val paths = mutableListOf<String>()
    private var protocol: String? = null
    private var expression: String? = null
    private val handlers = mutableListOf<CaddyHandler>()
    private val logFields = mutableListOf<CaddyLogAppend>()
    private val responseHeaders = linkedMapOf<String, String>()
    private var terminal: Boolean? = null

    fun logField(
        key: String,
        value: String,
    ) {
        logFields.add(CaddyLogAppend(key, value))
    }

    fun responseHeader(
        name: String,
        value: String,
    ) {
        responseHeaders[name] = value
    }

    fun host(vararg hosts: String) {
        this.hosts.addAll(hosts)
    }

    fun path(vararg paths: String) {
        this.paths.addAll(paths)
    }

    fun protocol(protocol: String) {
        this.protocol = protocol
    }

    fun expression(expression: String) {
        this.expression = expression
    }

    fun respond(
        statusCode: Int,
        body: String? = null,
    ) {
        handlers.add(CaddyStaticResponse(statusCode = statusCode, body = body))
    }

    fun abort() {
        handlers.add(CaddyStaticResponse(abort = true))
    }

    fun error(
        statusCode: Int,
        message: String? = null,
    ) {
        handlers.add(CaddyError(statusCode = statusCode, error = message))
    }

    fun redirect(
        location: String,
        statusCode: Int = 308,
    ) {
        handlers.add(
            CaddyStaticResponse(statusCode = statusCode, headers = mapOf("Location" to listOf(location))),
        )
    }

    fun fileServer(
        root: String,
        indexNames: List<String>? = null,
        statusCode: Int? = null,
    ) {
        handlers.add(CaddyFileServer(root = root, indexNames = indexNames, statusCode = statusCode))
    }

    // the handlers after it see at most maxSize bytes of request body
    fun maxRequestBodySize(maxSize: Long) {
        handlers.add(CaddyRequestBody(maxSize = maxSize))
    }

    fun rewrite(uri: String) {
        handlers.add(CaddyRewrite(uri = uri))
    }

    fun reverseProxy(
        vararg upstreams: String,
        healthCheck: CaddyActiveHealthCheck? = null,
    ) {
        handlers.add(
            CaddyReverseProxy(
                upstreams = upstreams.map { CaddyUpstream(it) },
                healthChecks = healthCheck?.let { CaddyHealthChecks(active = it) },
            ),
        )
    }

    fun handler(handler: CaddyHandler) {
        handlers.add(handler)
    }

    fun terminal() {
        terminal = true
    }

    fun build(): CaddyRoute {
        require(handlers.isNotEmpty()) { "a route must declare at least one handler" }
        val match =
            CaddyMatch(
                host = hosts.toList().ifEmpty { null },
                path = paths.toList().ifEmpty { null },
                protocol = protocol,
                expression = expression,
            ).takeIf { it.host != null || it.path != null || it.protocol != null || it.expression != null }
        val headers =
            responseHeaders
                .takeIf { it.isNotEmpty() }
                ?.let {
                    CaddyHeaders(
                        response =
                            CaddyHeaderOps(
                                set = it.mapValues { (_, value) -> listOf(value) },
                                deferred = true,
                            ),
                    )
                }
        return CaddyRoute(
            match = match?.let { listOf(it) },
            handle = logFields.toList() + listOfNotNull(headers) + handlers.toList(),
            terminal = terminal,
        )
    }
}
