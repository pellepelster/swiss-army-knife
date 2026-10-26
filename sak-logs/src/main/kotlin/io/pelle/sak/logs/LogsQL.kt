package io.pelle.sak.logs

// a logsql string literal, victorialogs unquotes it like go's strconv.Unquote
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// the entries of the site, every entry for all sites
fun streamFilter(site: Site) = if (site == Site.ALL) "*" else "_stream:{${SiteMetricsService.SITE}=${quoted(site.id)}}"
