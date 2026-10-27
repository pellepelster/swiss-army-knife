package io.pelle.sak.backend

import io.pelle.sak.config.SakConfig
import io.pelle.sak.logs.ALL_SITES
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

// all stands for every site in the console's log views
val RESERVED_SITES = setOf(S3_SITE, CONSOLE_SITE, REDIRECT_SITE, UNMATCHED_SITE, ALL_SITES)

// the buckets served at their domains are sites of their own, named like the bucket, whose names
// are no reserved ones (S3BucketService); the redirected domains belong to the redirect site
fun sites(
    config: SakConfig,
    bucketDomains: Map<String, List<String>>,
    redirectedDomains: List<String>,
): List<Site> =
    bucketDomains.filterValues { it.isNotEmpty() }.map { (bucket, domains) -> Site(bucket, domains) } +
        listOfNotNull(
            Site(S3_SITE, listOf(s3Host(config))),
            config.auth?.let { Site(CONSOLE_SITE, listOf(config.consoleHost)) },
            Site(REDIRECT_SITE, redirectedDomains),
            Site(UNMATCHED_SITE, emptyList()),
        )
