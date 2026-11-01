package io.pelle.sak.i18n

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

// the languages of every text; english is the source and the fallback
val LANGUAGES = listOf("en", "de")

// the plural forms a language may have (CLDR)
val PLURAL_FORMS = setOf("zero", "one", "two", "few", "many", "other")

// the platforms that read the texts of an app, each its own part of the section of the app
val PLATFORMS = listOf("backend", "web", "android")

// the parts of the section of sakctl: both, the command line, the window
val CTL_PARTS = listOf("common", "cli", "ui")

private val PLACEHOLDER = Regex("""%\{([a-z_]+)}""")
private val KEY = Regex("""[a-z][a-z0-9_]*""")

// the names of the placeholders of a text, in the order they first appear
fun placeholders(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.distinct().toList()

fun String.replacePlaceholders(transform: (String) -> String): String = PLACEHOLDER.replace(this) { transform(it.groupValues[1]) }

// a text of texts.yml
sealed interface Text {
    // the texts of a language, one per plural form, e.g. for its placeholders
    fun all(language: String): List<String>
}

// the same in every language, e.g. a name
data class Fixed(
    val text: String,
) : Text {
    override fun all(language: String) = listOf(text)
}

// a text per language
data class Translated(
    val texts: Map<String, String>,
) : Text {
    override fun all(language: String) = listOf(texts.getValue(language))
}

// the plural forms of a text per language, e.g. one and other
data class Plural(
    val forms: Map<String, Map<String, String>>,
) : Text {
    override fun all(language: String) = forms.getValue(language).values.toList()
}

// the keys of a part of the texts and their texts or the keys below them, in the order of texts.yml
sealed interface Node

data class Leaf(
    val text: Text,
) : Node

data class Group(
    val children: Map<String, Node>,
) : Node {
    // every text below the group with its dotted path, e.g. notes.too_many_photos
    fun leaves(prefix: String = ""): List<Pair<String, Text>> =
        children.flatMap { (key, node) ->
            when (node) {
                is Leaf -> listOf("$prefix$key" to node.text)
                is Group -> node.leaves("$prefix$key.")
            }
        }

    fun group(key: String): Group? = children[key] as? Group

    companion object {
        val EMPTY = Group(emptyMap())
    }
}

class TextsException(
    val problems: List<String>,
) : RuntimeException("i18n/texts.yml has problems:\n" + problems.joinToString("\n") { "  $it" })

// i18n/texts.yml read and checked: every text has every language with the same placeholders, every
// section is one texts.yml describes
class Texts private constructor(
    val root: Group,
) {
    // the texts of no app for all platforms and for one, e.g. common and web
    fun platform(name: String): Group = root.group(name) ?: Group.EMPTY

    // the sections of the apps, e.g. notes, without sakctl
    val apps: Map<String, Group>
        get() = root.children.filterKeys { it !in TOP_LEVEL && it != "ctl" }.mapValues { it.value as Group }

    val ctl: Group get() = root.group("ctl") ?: Group.EMPTY

    companion object {
        private val TOP_LEVEL = listOf("common") + PLATFORMS

        fun parse(yaml: String): Texts {
            val problems = mutableListOf<String>()
            // a key twice in one map would silently replace the first one
            val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
            val loaded =
                try {
                    Yaml(SafeConstructor(options)).load<Any?>(yaml)
                } catch (e: org.yaml.snakeyaml.error.YAMLException) {
                    throw TextsException(listOf(e.message ?: e.toString()))
                } ?: return Texts(Group.EMPTY)
            val root = node(loaded, "", problems) as? Group ?: throw TextsException(listOf("texts.yml is no map of sections"))
            sections(root, problems)
            if (problems.isNotEmpty()) throw TextsException(problems)
            return Texts(root)
        }

        private fun sections(
            root: Group,
            problems: MutableList<String>,
        ) {
            for ((name, section) in root.children) {
                if (section !is Group) {
                    problems += "$name is a text, a section has keys"
                    continue
                }
                val parts =
                    when (name) {
                        in TOP_LEVEL -> continue
                        "ctl" -> CTL_PARTS
                        else -> listOf("common") + PLATFORMS
                    }
                for ((part, node) in section.children) {
                    if (part !in parts) {
                        problems += "$name.$part is no part of a section, they are ${parts.joinToString()}"
                    } else if (node !is Group) {
                        problems += "$name.$part is a text, a part has keys"
                    }
                }
            }
        }

        private fun node(
            value: Any?,
            path: String,
            problems: MutableList<String>,
        ): Node? =
            when {
                value is String -> Leaf(Fixed(value))
                value is Map<*, *> && "en" in value.keys -> leaf(value, path, problems)?.let(::Leaf)
                value is Map<*, *> -> {
                    val children = linkedMapOf<String, Node>()
                    for ((key, child) in value) {
                        val name = key.toString()
                        val childPath = if (path.isEmpty()) name else "$path.$name"
                        if (!KEY.matches(name)) problems += "$childPath is no key: lower case letters, digits and _"
                        node(child, childPath, problems)?.let { children[name] = it }
                    }
                    Group(children)
                }
                else -> {
                    problems += "$path is \"$value\", not a text: quote it"
                    null
                }
            }

        private fun leaf(
            value: Map<*, *>,
            path: String,
            problems: MutableList<String>,
        ): Text? {
            val missing = LANGUAGES.filter { it !in value.keys }
            val unknown = value.keys.map { it.toString() }.filter { it !in LANGUAGES }
            if (missing.isNotEmpty()) problems += "$path has no ${missing.joinToString()}"
            if (unknown.isNotEmpty()) problems += "$path has ${unknown.joinToString()}, no language of ${LANGUAGES.joinToString()}"
            if (missing.isNotEmpty() || unknown.isNotEmpty()) return null
            val text =
                if (LANGUAGES.all { value[it] is String }) {
                    Translated(LANGUAGES.associateWith { value[it] as String })
                } else if (LANGUAGES.all { value[it] is Map<*, *> }) {
                    plural(value, path, problems) ?: return null
                } else {
                    problems += "$path is a text in one language and a plural or no text in another"
                    return null
                }
            val english = placeholders(text.all("en").joinToString(" ")).toSet()
            for (language in LANGUAGES.drop(1)) {
                val other = placeholders(text.all(language).joinToString(" ")).toSet()
                if (other != english) problems += "$path in $language has the placeholders $other, english $english"
            }
            return text
        }

        private fun plural(
            value: Map<*, *>,
            path: String,
            problems: MutableList<String>,
        ): Plural? {
            val forms =
                LANGUAGES.associateWith { language ->
                    (value[language] as Map<*, *>).entries.associate { (form, text) -> form.toString() to text }
                }
            var valid = true
            for ((language, texts) in forms) {
                val unknown = texts.keys - PLURAL_FORMS
                if (unknown.isNotEmpty()) problems += "$path in $language has ${unknown.joinToString()}, no plural form".also { valid = false }
                if ("other" !in texts) problems += "$path in $language has no plural form other".also { valid = false }
                if (texts.values.any { it !is String }) problems += "$path in $language has a form that is no text".also { valid = false }
            }
            if (!valid) return null
            return Plural(forms.mapValues { (_, texts) -> texts.mapValues { it.value as String } })
        }
    }
}

// two parts of the texts as one, e.g. notes.common and notes.web; a key in both is a problem
fun merge(
    parts: List<Pair<String, Group>>,
    problems: MutableList<String>,
): Group {
    fun into(
        target: LinkedHashMap<String, Any>,
        group: Group,
        source: String,
        path: String,
        origins: MutableMap<String, String>,
    ) {
        for ((key, node) in group.children) {
            val childPath = "$path$key"
            when (node) {
                is Leaf -> {
                    val existing = origins[childPath]
                    if (existing != null || target[key] != null) {
                        problems += "$childPath is in ${existing ?: "a group of"} and $source"
                    } else {
                        target[key] = node
                        origins[childPath] = source
                    }
                }
                is Group -> {
                    @Suppress("UNCHECKED_CAST")
                    val child =
                        when (val existing = target[key]) {
                            null -> linkedMapOf<String, Any>().also { target[key] = it }
                            is LinkedHashMap<*, *> -> existing as LinkedHashMap<String, Any>
                            else -> {
                                problems += "$childPath is a text in ${origins[childPath]} and has keys in $source"
                                continue
                            }
                        }
                    into(child, node, source, "$childPath.", origins)
                }
            }
        }
    }

    fun build(map: Map<String, Any>): Group =
        Group(
            map.mapValues { (_, value) ->
                @Suppress("UNCHECKED_CAST")
                if (value is Leaf) value else build(value as Map<String, Any>)
            },
        )

    val merged = linkedMapOf<String, Any>()
    val origins = mutableMapOf<String, String>()
    for ((source, group) in parts) into(merged, group, source, "", origins)
    return build(merged)
}
