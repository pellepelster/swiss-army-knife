package io.pelle.sak.controller

import io.pelle.sak.config.SakConfig
import io.pelle.sak.logs.Site
import io.pelle.sak.logs.SiteMetricsService

// caddy appends the site to every access log entry (log_append), it is the only stream field
// in victorialogs and what the console's metrics are queried by
const val SITE_LOG_FIELD = SiteMetricsService.SITE

const val S3_SITE = "s3"

const val CONSOLE_SITE = "console"

// the http to https redirect and the redirected domains
const val REDIRECT_SITE = "redirect"

// requests for hosts nothing is configured for, closed without an answer
const val UNMATCHED_SITE = "unmatched"

val RESERVED_SITES = setOf(S3_SITE, CONSOLE_SITE, REDIRECT_SITE, UNMATCHED_SITE)

// the buckets served at their domains are sites of their own, named like the bucket
fun sites(config: SakConfig): List<Site> {
    val buckets = config.s3.buckets.filterValues { it.domains.isNotEmpty() }
    buckets.keys.firstOrNull { it in RESERVED_SITES }?.let {
        throw IllegalArgumentException("bucket '$it' is served at its domains, its name is reserved for a site")
    }

    return buckets.map { (bucket, bucketConfig) -> Site(bucket, bucketConfig.domains) } +
        listOfNotNull(
            Site(S3_SITE, listOf(s3Host(config))),
            config.auth?.let { Site(CONSOLE_SITE, listOf(config.consoleHost)) },
            Site(REDIRECT_SITE, config.redirects.keys.toList()),
            Site(UNMATCHED_SITE, emptyList()),
        )
}
