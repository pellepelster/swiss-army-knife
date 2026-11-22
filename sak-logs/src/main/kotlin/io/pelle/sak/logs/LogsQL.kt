package io.pelle.sak.logs

// a logsql string literal, victorialogs unquotes it like go's strconv.Unquote
internal fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// the entries of the site
internal fun streamFilter(site: Site) = "_stream:{${SiteMetricsService.SITE}=${quoted(site.id)}}"
