package io.pelle.sak.controller

import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.caddy.CaddyException
import io.pelle.sak.caddy.CaddyManager
import io.pelle.sak.caddy.caddyConfig
import io.pelle.sak.caddy.model.CaddyConfig
import io.pelle.sak.config.SakConfig
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import io.pelle.sak.s3.storage.BlobPath
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

const val S3_PATH_PREFIX = "/s3"

fun s3Host(config: SakConfig) = "s3.${config.rootDomain}"

fun bucketPath(bucket: String) = BlobPath(listOf("buckets", bucket))

fun bucketObjectsDir(
    config: SakConfig,
    bucket: String,
): Path = bucketPath(bucket).segments.fold(config.s3DataDir) { dir, segment -> dir.resolve(segment) }.resolve(BinaryStorageS3Backend.DATA)

fun caddyConfig(
    config: SakConfig,
    port: Int,
): CaddyConfig =
    caddyConfig {
        server("sak") {
            listen(":80", ":443")
            // the ingress writes the access log to the file its caddyfile configures
            enableAccessLog()

            route {
                protocol("http")
                redirect("https://{http.request.host}{http.request.uri}")
                terminal()
            }

            route {
                host(s3Host(config))
                rewrite("$S3_PATH_PREFIX{http.request.uri}")
                reverseProxy("localhost:$port")
                terminal()
            }

            config.s3.buckets
                .filterValues { it.domains.isNotEmpty() }
                .forEach { (bucket, bucketConfig) ->
                    route {
                        host(*bucketConfig.domains.toTypedArray())
                        fileServer(root = bucketObjectsDir(config, bucket).toString(), indexNames = listOf("index.html"))
                        terminal()
                    }
                }

            config.redirects.forEach { (from, to) ->
                route {
                    host(from)
                    redirect("https://$to{http.request.uri}")
                    terminal()
                }
            }

            route {
                abort()
            }
        }
    }

class CaddyReconciler(
    private val caddyManager: CaddyManager,
    private val config: CaddyConfig,
    private val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
) {
    private val logger = KotlinLogging.logger {}

    private var lastAttemptFailed = true

    private var executor: ScheduledExecutorService? = null

    @Synchronized
    fun reconcile() {
        try {
            caddyManager.updateApps(config)
            if (lastAttemptFailed) {
                logger.info { "configured caddy at '${caddyManager.adminUrl}'" }
            }
            lastAttemptFailed = false
        } catch (e: CaddyException) {
            logger.warn(e) { "caddy at '${caddyManager.adminUrl}' rejected the configuration" }
            lastAttemptFailed = true
        } catch (e: Exception) {
            val message = { "caddy at '${caddyManager.adminUrl}' is not reachable (${e.message}), retrying periodically" }
            if (lastAttemptFailed) logger.debug(message) else logger.warn(message)
            lastAttemptFailed = true
        }
    }

    fun start() {
        executor =
            Executors.newSingleThreadScheduledExecutor { Thread(it, "caddy-reconciler").apply { isDaemon = true } }.also {
                it.scheduleWithFixedDelay(::reconcile, 0, intervalSeconds, TimeUnit.SECONDS)
            }
    }

    fun stop() {
        executor?.shutdownNow()
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 30L
    }
}
