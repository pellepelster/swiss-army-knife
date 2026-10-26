package io.pelle.sak.logs

enum class UserAgentGroup(
    val key: String,
) {
    ANTHROPIC("anthropic"),
    OPENAI("openai"),
    GOOGLE("google"),
    BING("bing"),
    META("meta"),
    AMAZON("amazon"),
    PERPLEXITY("perplexity"),
    AI_CRAWLER("ai-crawler"),
    SEARCH_ENGINE("search-engine"),
    SEO_CRAWLER("seo-crawler"),
    FEED_READER("feed-reader"),
    SECURITY_SCANNER("security-scanner"),
    EXPLOIT("exploit"),
    SDK("sdk"),
    CHROME("chrome"),
    FIREFOX("firefox"),
    SAFARI("safari"),
    EDGE("edge"),
    OTHER_BROWSER("other-browser"),
    OTHER("other"),
}

// the first matching rule wins, so bots that pretend to be browsers come before the browsers
object UserAgentClassifier {
    private val RULES: List<Pair<UserAgentGroup, Regex>> =
        listOf(
            UserAgentGroup.ANTHROPIC to "Claude-?(?:User|Bot|SearchBot)|anthropic",
            UserAgentGroup.OPENAI to "GPTBot|ChatGPT-User|OAI-SearchBot|openai\\.com",
            UserAgentGroup.GOOGLE to
                "Googlebot|Google-Extended|GoogleSecurityScanner|Google-InspectionTool|" +
                "Chrome Privacy Preserving Prefetch Proxy|Google-Safety",
            UserAgentGroup.BING to "bingbot|BingPreview|msnbot",
            UserAgentGroup.META to "facebookexternalhit|meta-externalagent|meta-webindexer|Facebot",
            UserAgentGroup.AMAZON to "Amazonbot|Amzn-SearchBot|Applebot",
            UserAgentGroup.PERPLEXITY to "PerplexityBot|Perplexity-User",
            UserAgentGroup.AI_CRAWLER to
                "YouBot|AzureAI-SearchBot|Reflectionbot|KagiTranslate|kagibot|DuckAssistBot|" +
                "Bytespider|CCBot|Diffbot|Timpibot|omgili",
            UserAgentGroup.SEARCH_ENGINE to
                "Baiduspider|SeznamBot|Qwantbot|Sogou|DuckDuckBot|marginalia|YandexBot|Slurp|Teoma",
            UserAgentGroup.SEO_CRAWLER to
                "AhrefsBot|SemrushBot|MJ12bot|DotBot|Barkrowler|Screaming ?Frog|BLEXBot|" +
                "outreach|wpbot|forms\\.gle",
            UserAgentGroup.FEED_READER to
                "Feedly|Inoreader|FreshRSS|Miniflux|rss-parser|NewsBlur|Tiny ?Tiny ?RSS|Feedbin|" +
                "granary|feedparser",
            UserAgentGroup.EXPLOIT to
                "cve-\\d{4}|vuln_scanner|wp2shell|owned-you|\\(\\)\\s*\\{\\s*:;|CT-WP-Probe|" +
                "CMS-Checker|sqlmap|nikto|_SHK",
            UserAgentGroup.SECURITY_SCANNER to
                "Censys|Shodan|InternetMeasurement|nmap|zgrab|masscan|l9explore|l9tcpid|Modat|" +
                "Netcraft|Palo Alto|Xpanse|Assetnote|CloudflareWebScanner|WanScanner|" +
                "visionheight|Infrawatch|paloaltonetworks",
            UserAgentGroup.SDK to
                "aws-sdk|Go-http-client|okhttp|python-requests|python-httpx|Python-urllib|aiohttp|" +
                "libredtail|Apache-HttpClient|Java/|node-fetch|axios|libcurl|^curl/|Wget|" +
                "PostmanRuntime|Dalvik|NetworkingExtension",
            UserAgentGroup.EDGE to "\\bEdg[eA]?/",
            UserAgentGroup.OTHER_BROWSER to "\\bOPR/|^Opera/|YaBrowser|UCBrowser|Whale/|SamsungBrowser",
            UserAgentGroup.CHROME to "HeadlessChrome|\\bChrome/|CriOS/",
            UserAgentGroup.FIREFOX to "\\bFirefox/|Thunderbird/|\\bGecko/\\d",
            UserAgentGroup.SAFARI to "Version/[\\d.]+ .*Safari|^Mozilla/5\\.0 \\(i(?:Phone|Pad)",
        ).map { (group, pattern) -> group to Regex(pattern, RegexOption.IGNORE_CASE) }

    // vector stores at most this much of a user agent, and java's regex backtracks, so the rules
    // only ever see a bounded input
    const val MAX_LENGTH = 512

    fun classify(userAgent: String): UserAgentGroup {
        val bounded = userAgent.take(MAX_LENGTH)
        return RULES.firstOrNull { (_, pattern) -> pattern.containsMatchIn(bounded) }?.first ?: UserAgentGroup.OTHER
    }
}
