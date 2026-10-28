package io.pelle.sak.backend

import io.pelle.sak.logs.Site
import io.pelle.sak.logs.SiteMetricsService
import io.pelle.sak.backend.hosting.S3BucketService
import io.pelle.sak.config.SakConfig

// caddy appends the site to every access log entry (log_append), it is the only stream field
// in victorialogs and what the access logs of a bucket are queried by
const val SITE_LOG_FIELD = SiteMetricsService.SITE

// sak-frontend at sak.<base_domain>
const val FRONTEND_SITE = SakConfig.FRONTEND_LABEL

// the api of sak-frontend and the s3 api at api.<base_domain>
const val API_SITE = SakConfig.API_LABEL

// the http to https redirect and the redirected domains
const val REDIRECT_SITE = "redirect"

// requests for hosts nothing is configured for, closed without an answer
const val UNMATCHED_SITE = "unmatched"

// no bucket is named like the sites of the controller
val RESERVED_SITES = setOf(FRONTEND_SITE, API_SITE, REDIRECT_SITE, UNMATCHED_SITE)

// a bucket served at its domains is a site of its own, named like the bucket, whose name is no
// reserved one (S3BucketService); null for an unknown bucket or one served at no domain
fun bucketSite(
    bucketService: S3BucketService,
    name: String,
): Site? = bucketService.byName(name)?.let { bucket -> bucketService.servedDomains(bucket).takeIf { it.isNotEmpty() }?.let { Site(bucket.name, it) } }
