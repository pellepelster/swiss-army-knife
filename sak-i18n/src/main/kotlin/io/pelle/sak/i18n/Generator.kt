package io.pelle.sak.i18n

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

const val SOURCE = "i18n/texts.yml"

// the catalogs of the backend, i18n/<catalog>.<language>.yml on the class path of every module
const val BACKEND_DIR = "sak-api/src/main/resources/i18n"
const val WEB_FILE = "sak-frontend/src/app/i18n/catalog.ts"
// the android apps (android/): the texts of no app go to core, the ones of an app to its module; the
// apps are the lines of android/apps.txt, relative to the repository (sak.root of the tests, the
// working directory of the run)
const val ANDROID_CORE_RES = "android/core/src/main/res"
const val ANDROID_APPS_FILE = "android/apps.txt"
val ANDROID_APPS: Map<String, String> =
    (System.getProperty("sak.root")?.let { Path.of(it) } ?: Path.of(""))
        .resolve(ANDROID_APPS_FILE)
        .readText()
        .lines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .associateWith { "android/$it/src/main/res" }
const val CTL_DIR = "sak-cli/src-tauri/locales"

private const val GENERATED = "generated from $SOURCE by sak-i18n (mise run i18n:generate), do not edit"

// the files each platform reads, written from texts.yml; the paths are relative to the repository
class Generator(
    private val texts: Texts,
) {
    private val problems = mutableListOf<String>()

    // the texts of a platform: common and its own part of no app, then common and its part of every
    // app that has one, below the name of the app
    fun platform(name: String): Group {
        val parts = mutableListOf("common" to texts.platform("common"), name to texts.platform(name))
        for ((app, section) in texts.apps) {
            val part = section.group(name) ?: continue
            val common = section.group("common") ?: Group.EMPTY
            parts += "$app.common" to Group(mapOf(app to common))
            parts += "$app.$name" to Group(mapOf(app to part))
        }
        return merge(parts, problems)
    }

    // the texts of android by the resources they go to: common and android into core, the common
    // and android part of each app with an android module into its module, below the name of the app
    fun android(): Map<String, Group> {
        val all = platform("android")
        if (all.children.isEmpty() && problems.isNotEmpty()) return emptyMap()
        val res = linkedMapOf(ANDROID_CORE_RES to merge(listOf("common" to texts.platform("common"), "android" to texts.platform("android")), problems))
        for ((app, dir) in ANDROID_APPS) {
            val section = texts.apps[app] ?: continue
            val parts = mutableListOf<Pair<String, Group>>()
            section.group("common")?.let { parts += "$app.common" to Group(mapOf(app to it)) }
            section.group("android")?.let { parts += "$app.android" to Group(mapOf(app to it)) }
            res[dir] = merge(parts, problems)
        }
        val unknown = texts.apps.filter { (app, section) -> section.group("android") != null && app !in ANDROID_APPS }.keys
        unknown.forEach { problems += "$it has texts of android, but no module of android (android/apps.txt)" }
        return res
    }

    // the texts of sakctl, a program of its own: the three parts of ctl, each below its name, as the
    // command line and the window are one program
    fun ctl(): Group = Group(CTL_PARTS.associateWith { part -> texts.ctl.group(part) ?: Group.EMPTY })

    fun files(): Map<String, String> {
        val backend = platform("backend")
        val web = platform("web")
        val android = android()
        val ctl = ctl()
        noPlurals("backend", backend)
        noPlurals("web", web)
        noPlurals("sakctl", ctl)
        for ((path, text) in backend.leaves()) {
            if (LANGUAGES.any { language -> text.all(language).any { '$' in it } }) {
                problems += "$path has a \$, the backend reads it as a variable of the environment"
            }
        }
        for ((catalog, node) in backend.children) {
            if (node !is Group) problems += "$catalog is a text of the backend, a catalog of the backend has keys"
        }
        if (problems.isNotEmpty()) throw TextsException(problems.distinct())
        val files = linkedMapOf<String, String>()
        for ((catalog, node) in backend.children) {
            if (node !is Group) continue
            for (language in LANGUAGES) {
                files["$BACKEND_DIR/$catalog.$language.yml"] = Yaml.write(node, language, "# the texts of the catalog $catalog in $language, $GENERATED")
            }
        }
        files[WEB_FILE] = TypeScript.write(web)
        for ((res, group) in android) {
            files["$res/values/strings.xml"] = AndroidXml.write(group, LANGUAGES.first(), problems)
            for (language in LANGUAGES.drop(1)) {
                files["$res/values-$language/strings.xml"] = AndroidXml.write(group, language, problems)
            }
        }
        for (language in LANGUAGES) {
            files["$CTL_DIR/$language.yml"] = Yaml.write(ctl, language, "# the texts of sakctl in $language, $GENERATED")
        }
        if (problems.isNotEmpty()) throw TextsException(problems.distinct())
        return files
    }

    private fun noPlurals(
        platform: String,
        group: Group,
    ) {
        for ((path, text) in group.leaves()) {
            if (text is Plural) problems += "$path is a plural, $platform has none: only android has plurals"
        }
    }

    companion object {
        fun of(root: Path): Generator = Generator(Texts.parse(root.resolve(SOURCE).readText()))

        // the generated files that are not as texts.yml says, with the ones that are left over
        fun outdated(root: Path): List<String> {
            val files = of(root).files()
            val changed = files.filter { (path, content) -> root.resolve(path).let { !it.exists() || it.readText() != content } }.keys
            return (changed + leftovers(root, files.keys)).sorted()
        }

        fun generate(root: Path): List<String> {
            val files = of(root).files()
            val written = mutableListOf<String>()
            for ((path, content) in files) {
                val file = root.resolve(path)
                if (file.exists() && file.readText() == content) continue
                Files.createDirectories(file.parent)
                Files.writeString(file, content)
                written += path
            }
            for (path in leftovers(root, files.keys)) {
                Files.delete(root.resolve(path))
                written += path
            }
            return written
        }

        // a catalog of the backend that texts.yml has no longer
        private fun leftovers(
            root: Path,
            generated: Set<String>,
        ): List<String> {
            val dir = root.resolve(BACKEND_DIR)
            if (!dir.isDirectory()) return emptyList()
            return dir
                .listDirectoryEntries("*.yml")
                .map { "$BACKEND_DIR/${it.name}" }
                .filter { it !in generated }
        }
    }
}

private fun quoted(
    text: String,
    quote: Char,
): String =
    buildString {
        append(quote)
        for (c in text) {
            when {
                c == '\\' -> append("\\\\")
                c == quote -> append('\\').append(c)
                c == '\n' -> append("\\n")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append(quote)
    }

private fun Text.text(language: String): String =
    when (this) {
        is Fixed -> text
        is Translated -> texts.getValue(language)
        is Plural -> error("a plural has no single text")
    }

// a catalog as yaml: nested keys, the texts quoted, a text of lines as a block
private object Yaml {
    fun write(
        group: Group,
        language: String,
        header: String,
    ): String {
        val lines = mutableListOf(header)
        write(group, language, "", lines)
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun write(
        group: Group,
        language: String,
        indent: String,
        lines: MutableList<String>,
    ) {
        for ((key, node) in group.children) {
            when (node) {
                is Group -> {
                    lines += "$indent$key:"
                    write(node, language, "$indent  ", lines)
                }
                is Leaf -> {
                    val text = node.text.text(language)
                    if ('\n' in text.trimEnd('\n') && text.lines().none { it.startsWith(" ") }) {
                        val chomp =
                            when {
                                !text.endsWith("\n") -> "-"
                                text.endsWith("\n\n") -> "+"
                                else -> ""
                            }
                        lines += "$indent$key: |$chomp"
                        text.removeSuffix("\n").split("\n").forEach { lines += if (it.isEmpty()) "" else "$indent  $it" }
                    } else {
                        lines += "$indent$key: ${quoted(text, '"')}"
                    }
                }
            }
        }
    }
}

// the catalog of the web apps: an object per language, its type gives the keys (TextKey)
private object TypeScript {
    private val IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")

    fun write(group: Group): String {
        val lines =
            mutableListOf(
                "// $GENERATED",
                "import { Paths } from './texts';",
                "",
                "// the texts of the web apps by language; german has every text of english (sak-i18n checks)",
                "export const CATALOG = {",
            )
        for (language in LANGUAGES) {
            lines += "  $language: {"
            write(group, language, "    ", lines)
            lines += "  },"
        }
        lines += "} as const;"
        lines += ""
        lines += "// every key of a text, e.g. login.title"
        lines += "export type TextKey = Paths<typeof CATALOG.en>;"
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun write(
        group: Group,
        language: String,
        indent: String,
        lines: MutableList<String>,
    ) {
        for ((key, node) in group.children) {
            val name = if (IDENTIFIER.matches(key)) key else quoted(key, '\'')
            when (node) {
                is Group -> {
                    lines += "$indent$name: {"
                    write(node, language, "$indent  ", lines)
                    lines += "$indent},"
                }
                is Leaf -> lines += "$indent$name: ${quoted(node.text.text(language), '\'')},"
            }
        }
    }
}

// the string resources of android: a key is its path with _, e.g. grocery_form_save; %{name}
// becomes %1$s by the order of english, %{count} a number, %1$d
private object AndroidXml {
    fun write(
        group: Group,
        language: String,
        problems: MutableList<String>,
    ): String {
        val default = language == LANGUAGES.first()
        val lines =
            mutableListOf(
                """<?xml version="1.0" encoding="utf-8"?>""",
                "<!-- the texts of the app in $language, $GENERATED -->",
                "<resources>",
            )
        val names = mutableMapOf<String, String>()
        for ((path, text) in group.leaves()) {
            val name = path.replace('.', '_')
            names.put(name, path)?.let { problems += "$path and $it are both $name on android" }
            val order = placeholders(text.all(LANGUAGES.first()).joinToString(" "))
            when (text) {
                is Fixed -> {
                    if (default) lines += """    <string name="$name" translatable="false">${escape(text.text, order)}</string>"""
                }
                is Translated -> lines += """    <string name="$name">${escape(text.texts.getValue(language), order)}</string>"""
                is Plural -> {
                    lines += """    <plurals name="$name">"""
                    for ((form, form_text) in text.forms.getValue(language)) {
                        lines += """        <item quantity="$form">${escape(form_text, order)}</item>"""
                    }
                    lines += "    </plurals>"
                }
            }
        }
        lines += "</resources>"
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun escape(
        text: String,
        order: List<String>,
    ): String {
        val formatted = order.isNotEmpty()
        val escaped =
            buildString {
                text.forEachIndexed { at, c ->
                    when {
                        c == '&' -> append("&amp;")
                        c == '<' -> append("&lt;")
                        c == '>' -> append("&gt;")
                        c == '"' -> append("\\\"")
                        c == '\'' -> append("\\'")
                        c == '\\' -> append("\\\\")
                        c == '\n' -> append("\\n")
                        c == '@' && at == 0 -> append("\\@")
                        c == '?' && at == 0 -> append("\\?")
                        c == '%' && formatted && !text.startsWith("%{", at) -> append("%%")
                        else -> append(c)
                    }
                }
            }
        return escaped.replacePlaceholders { name -> "%${order.indexOf(name) + 1}\$${if (name == "count") "d" else "s"}" }
    }
}
