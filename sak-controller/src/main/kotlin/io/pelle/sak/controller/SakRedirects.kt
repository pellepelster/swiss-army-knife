package io.pelle.sak.controller

import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.config.SakConfig
import io.pelle.sak.model.ingress.RedirectService

private val logger = KotlinLogging.logger {}

// the domains the controller serves itself, none of them can be redirected
fun reservedDomains(config: SakConfig): Set<String> =
    setOf(config.rootDomain, s3Host(config), config.consoleHost) + config.s3.buckets.values.flatMap { it.domains }

// every redirected domain with the domain it leads to; a bucket no longer served at a domain
// leads nowhere, its redirect is left out
fun RedirectService.locations(): Map<String, String> =
    all()
        .mapNotNull { redirect ->
            val location = location(redirect.target)
            if (location == null) logger.warn { "the redirect of '${redirect.sourceDomain}' leads nowhere, it is left out" }
            location?.let { redirect.sourceDomain to it }
        }.toMap()
