package io.pelle.sak.backend

import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.config.SakConfig
import io.pelle.sak.ingress.CaddyException
import io.pelle.sak.ingress.CaddyManager
import io.pelle.sak.ingress.caddyConfig
import io.pelle.sak.ingress.model.CaddyConfig
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import io.pelle.sak.s3.storage.BlobPath
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

const val S3_PATH_PREFIX = "/s3"

fun bucketPath(bucket: String) = BlobPath(listOf("buckets", bucket))

fun bucketObjectsDir(
    config: SakConfig,
    bucket: String,
): Path = bucketPath(bucket).segments.fold(config.s3DataDir) { dir, segment -> dir.resolve(segment) }.resolve(BinaryStorageS3Backend.DATA)

// the base domain (the root domain or <name>.<root_domain>, by the dns mode) leads to
// sak-frontend, unless a bucket is served at it or it is redirected elsewhere; sak-frontend is
// only served when the login is configured
fun redirectsRootDomain(
    config: SakConfig,
    bucketDomains: Map<String, List<String>>,
    redirectedDomains: Collection<String>,
): Boolean =
    config.auth != null &&
        bucketDomains.values.none { config.baseDomain in it } &&
        config.baseDomain !in redirectedDomains

// bucketDomains are the domains each bucket is served at, redirects maps every redirected domain
// to the domain it leads to
fun caddyConfig(
    config: SakConfig,
    bucketDomains: Map<String, List<String>>,
    redirects: Map<String, String>,
    port: Int,
): CaddyConfig =
    caddyConfig {
        config.acmeCa?.let { acmeCa(it) }
        server("sak") {
            listen(":80", ":443")
            // the ingress writes the access log to the file its caddyfile configures
            enableAccessLog()

            route {
                protocol("http")
                logField(SITE_LOG_FIELD, REDIRECT_SITE)
                redirect("https://{http.request.host}{http.request.uri}")
                terminal()
            }

            if (config.auth != null) {
                // sak-frontend, served by the controller; the api and the s3 api the controller
                // serves as well belong to api.<base_domain> only
                route {
                    host(config.frontendHost)
                    path(S3_PATH_PREFIX, "$S3_PATH_PREFIX/*", API_PATH_PREFIX, "$API_PATH_PREFIX/*")
                    logField(SITE_LOG_FIELD, FRONTEND_SITE)
                    respond(404)
                    terminal()
                }
                route {
                    host(config.frontendHost)
                    logField(SITE_LOG_FIELD, FRONTEND_SITE)
                    responseHeader("Strict-Transport-Security", "max-age=31536000")
                    maxRequestBodySize(APPS_MAX_BODY_SIZE)
                    reverseProxy("localhost:$port")
                    terminal()
                }

                // the api of sak-frontend, below /api of the controller; no bucket is named v1,
                // bucket names have at least three characters
                route {
                    host(config.apiHost)
                    path(API_VERSION, "$API_VERSION/*")
                    logField(SITE_LOG_FIELD, API_SITE)
                    responseHeader("Strict-Transport-Security", "max-age=31536000")
                    maxRequestBodySize(APPS_MAX_BODY_SIZE)
                    rewrite("$API_PATH_PREFIX{http.request.uri}")
                    reverseProxy("localhost:$port")
                    terminal()
                }
            }

            // everything else at api.<base_domain> is the s3 api, below /s3 of the controller; hsts
            // holds for a host, so as for the api of sak-frontend. The bucket domains and the
            // redirects have none, the owner of a site decides that
            route {
                host(config.apiHost)
                logField(SITE_LOG_FIELD, API_SITE)
                responseHeader("Strict-Transport-Security", "max-age=31536000")
                rewrite("$S3_PATH_PREFIX{http.request.uri}")
                reverseProxy("localhost:$port")
                terminal()
            }

            bucketDomains
                .filterValues { it.isNotEmpty() }
                .forEach { (bucket, domains) ->
                    route {
                        host(*domains.toTypedArray())
                        logField(SITE_LOG_FIELD, bucket)
                        fileServer(root = bucketObjectsDir(config, bucket).toString(), indexNames = listOf("index.html"))
                        terminal()
                    }
                }

            redirects.forEach { (from, to) ->
                route {
                    host(from)
                    logField(SITE_LOG_FIELD, REDIRECT_SITE)
                    redirect("https://$to{http.request.uri}")
                    terminal()
                }
            }

            if (redirectsRootDomain(config, bucketDomains, redirects.keys)) {
                route {
                    host(config.baseDomain)
                    logField(SITE_LOG_FIELD, REDIRECT_SITE)
                    redirect("https://${config.frontendHost}{http.request.uri}")
                    terminal()
                }
            }

            route {
                logField(SITE_LOG_FIELD, UNMATCHED_SITE)
                abort()
            }
        }
    }

// pushes the configuration to caddy periodically and when asked to, built anew every time
class CaddyReconciler(
    private val caddyManager: CaddyManager,
    private val config: () -> CaddyConfig,
    private val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
) {
    private val logger = KotlinLogging.logger {}

    // whether the last attempt configured caddy, null before the first one; the first failure and
    // the first success are told, the failures repeating it at debug only
    private var configured: Boolean? = null

    private var executor: ScheduledExecutorService? = null

    @Synchronized
    fun reconcile() {
        try {
            caddyManager.updateApps(config())
            if (configured != true) {
                logger.info { "configured caddy at '${caddyManager.adminUrl}'" }
            }
            configured = true
        } catch (e: CaddyException) {
            logger.warn(e) { "caddy at '${caddyManager.adminUrl}' rejected the configuration" }
            configured = false
        } catch (e: Exception) {
            val message = { "caddy at '${caddyManager.adminUrl}' is not reachable (${e.message}), retrying periodically" }
            if (configured == false) logger.debug(message) else logger.warn(message)
            configured = false
        }
    }

    fun start() {
        executor =
            Executors.newSingleThreadScheduledExecutor { Thread(it, "caddy-reconciler").apply { isDaemon = true } }.also {
                it.scheduleWithFixedDelay(::reconcile, 0, intervalSeconds, TimeUnit.SECONDS)
            }
    }

    // right away, e.g. after the redirects changed, without waiting for the next period
    fun reconcileNow() {
        executor?.execute(::reconcile)
    }

    fun stop() {
        executor?.shutdownNow()
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 30L
    }
}
