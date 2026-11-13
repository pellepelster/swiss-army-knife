package io.pelle.sak.api

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.config.yaml.YamlConfig
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// the languages of the texts a user reads; english is the source of every text and the fallback
enum class Language(
    val tag: String,
) {
    EN("en"),
    DE("de"),
    ;

    companion object {
        val FALLBACK = EN

        // the first language of an Accept-Language header there is a catalog for, else english
        fun of(acceptLanguage: String?): Language {
            val ranges = runCatching { Locale.LanguageRange.parse(acceptLanguage.orEmpty()) }.getOrDefault(emptyList())
            return ranges
                .asSequence()
                .filter { it.weight > 0 }
                .mapNotNull { range -> entries.firstOrNull { it.tag == range.range.substringBefore('-').lowercase() } }
                .firstOrNull() ?: FALLBACK
        }
    }
}

fun ApplicationCall.language(): Language = Language.of(request.headers[HttpHeaders.AcceptLanguage])

// a text in a catalog: the catalog, e.g. backend, and the path of the text in it, e.g.
// notes.too_many_photos. Made by a Catalog only, which keeps all its keys
data class TextKey(
    val catalog: String,
    val path: String,
) {
    // the code of the text in an error body, e.g. backend.notes.too_many_photos
    val code get() = "$catalog.$path"

    override fun toString() = code
}

// a text a user reads: its key and the values of its placeholders, %{name} in the catalogs. A value
// that is a text, a key or texts joined is read in the same language
data class Text(
    val key: TextKey,
    val args: Map<String, Any?> = emptyMap(),
) {
    constructor(key: TextKey, vararg args: Pair<String, Any?>) : this(key, args.toMap())

    fun render(language: Language): String = Catalogs.render(key, args, language)

    // english, e.g. in a log line or a test
    override fun toString() = render(Language.FALLBACK)
}

fun TextKey.render(language: Language): String = Text(this).render(language)

// the value of a placeholder of several texts, e.g. "8.8.8.8 has no A; 9.9.9.9 has no AAAA"
data class Joined(
    val parts: List<Any?>,
    val separator: String,
)

// the texts of one module, `i18n/<name>.<language>.yml` on the class path, generated from
// i18n/texts.yml. Each key is a property made with text(); a test of the module checks the files
// against them (catalogProblems). Ktor reads $NAME in yaml as an environment variable, so sak-i18n
// refuses a text with a $
abstract class Catalog(
    val name: String,
) {
    protected fun text(path: String) = TextKey(name, path)
}

// the texts of every catalog and language, read once
object Catalogs {
    private val loaded = ConcurrentHashMap<Pair<String, Language>, Map<String, String>>()

    private val PLACEHOLDER = Regex("""%\{([a-z_]+)}""")

    // the paths of the texts of a catalog in a language and the texts, empty without its file
    fun texts(
        catalog: String,
        language: Language,
    ): Map<String, String> =
        loaded.computeIfAbsent(catalog to language) { (name, lang) ->
            val config = YamlConfig("i18n/$name.${lang.tag}.yml") ?: return@computeIfAbsent emptyMap()
            flatten(config.toMap())
        }

    // the nested maps of a catalog as paths, e.g. category.bakery
    private fun flatten(
        map: Map<String, Any?>,
        prefix: String = "",
    ): Map<String, String> =
        map.entries
            .flatMap { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is Map<*, *> -> flatten(value as Map<String, Any?>, "$prefix$key.").entries.map { it.toPair() }
                    else -> listOf("$prefix$key" to value.toString())
                }
            }.toMap()

    // the names of the placeholders of a text
    fun placeholders(text: String): Set<String> = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()

    fun render(
        key: TextKey,
        args: Map<String, Any?>,
        language: Language,
    ): String {
        val text =
            texts(key.catalog, language)[key.path]
                ?: texts(key.catalog, Language.FALLBACK)[key.path]
                ?: error("there is no text $key")
        return PLACEHOLDER.replace(text) { match -> renderValue(args[match.groupValues[1]], language) }
    }

    private fun renderValue(
        value: Any?,
        language: Language,
    ): String =
        when (value) {
            is Text -> value.render(language)
            is TextKey -> value.render(language)
            is Joined -> value.parts.joinToString(value.separator) { renderValue(it, language) }
            else -> value.toString()
        }
}

// the texts of the api itself, what every route answers alike (backend.api in i18n/texts.yml)
object ApiTexts : Catalog("api") {
    val MALFORMED_REQUEST = text("malformed_request")
    val TOO_LARGE_REQUEST = text("too_large_request")
    val UNEXPECTED_ERROR = text("unexpected_error")
    val INVALID_OFFSET = text("invalid_offset")
    val INVALID_LIMIT = text("invalid_limit")
    val UNAUTHORIZED = text("unauthorized")
    val FORBIDDEN = text("forbidden")
    val NOT_FOUND = text("not_found")
    val TOO_MANY_REQUESTS = text("too_many_requests")
    val UNAVAILABLE = text("unavailable")
}
