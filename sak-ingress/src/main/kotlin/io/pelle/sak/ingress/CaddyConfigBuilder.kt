package io.pelle.sak.ingress

import io.pelle.sak.ingress.model.CaddyApps
import io.pelle.sak.ingress.model.CaddyConfig
import io.pelle.sak.ingress.model.CaddyFileServer
import io.pelle.sak.ingress.model.CaddyHandler
import io.pelle.sak.ingress.model.CaddyHeaderOps
import io.pelle.sak.ingress.model.CaddyHeaders
import io.pelle.sak.ingress.model.CaddyHttpApp
import io.pelle.sak.ingress.model.CaddyIssuer
import io.pelle.sak.ingress.model.CaddyLogAppend
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
    private var acmeCa: String? = null

    fun server(
        name: String,
        block: CaddyServerBuilder.() -> Unit,
    ) {
        servers[name] = CaddyServerBuilder().apply(block).build()
    }

    // the certificates from the acme ca of this directory, e.g. the staging ca of let's encrypt
    // for tests, instead of caddy's default issuers
    fun acmeCa(directory: String) {
        acmeCa = directory
    }

    fun build(): CaddyConfig =
        CaddyConfig(
            apps =
                CaddyApps(
                    http = CaddyHttpApp(servers.toMap()),
                    tls = acmeCa?.let { CaddyTlsApp(CaddyTlsAutomation(listOf(CaddyTlsPolicy(listOf(CaddyIssuer(ca = it)))))) },
                ),
        )
}

@CaddyDsl
class CaddyServerBuilder {
    private val listen = mutableListOf<String>()
    private val routes = mutableListOf<CaddyRoute>()
    private var logs: CaddyServerLogs? = null

    fun enableAccessLog() {
        logs = CaddyServerLogs
    }

    fun listen(vararg addresses: String) {
        listen.addAll(addresses)
    }

    fun route(block: CaddyRouteBuilder.() -> Unit) {
        routes.add(CaddyRouteBuilder().apply(block).build())
    }

    fun build(): CaddyServer {
        require(listen.isNotEmpty()) { "a server must declare at least one listen address" }
        return CaddyServer(
            listen = listen.toList(),
            routes = routes.toList(),
            logs = logs,
        )
    }
}

@CaddyDsl
class CaddyRouteBuilder {
    private val hosts = mutableListOf<String>()
    private val paths = mutableListOf<String>()
    private var protocol: String? = null
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

    fun respond(statusCode: Int) {
        handlers.add(CaddyStaticResponse(statusCode = statusCode))
    }

    fun abort() {
        handlers.add(CaddyStaticResponse(abort = true))
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
    ) {
        handlers.add(CaddyFileServer(root = root, indexNames = indexNames))
    }

    // the handlers after it see at most maxSize bytes of request body
    fun maxRequestBodySize(maxSize: Long) {
        handlers.add(CaddyRequestBody(maxSize = maxSize))
    }

    fun rewrite(uri: String) {
        handlers.add(CaddyRewrite(uri = uri))
    }

    fun reverseProxy(vararg upstreams: String) {
        handlers.add(CaddyReverseProxy(upstreams = upstreams.map { CaddyUpstream(it) }))
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
            ).takeIf { it.host != null || it.path != null || it.protocol != null }
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
