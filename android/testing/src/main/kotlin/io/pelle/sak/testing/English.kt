package io.pelle.sak.testing

import io.pelle.sak.core.UiText
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

// the package of the app whose tests run, e.g. io.pelle.sak.grocery, set by its build (sak.app.package)
private val appPackage: String by lazy { System.getProperty("sak.app.package") ?: error("sak.app.package is not set for the tests") }

// the english texts of core and of the app by their names, unescaped as android reads them. The tests
// run in the directory of the app's module: without core's texts this fails loudly instead of answering
// wrong texts; the app may have none (e.g. the tests of testing)
private val strings: Map<String, String> by lazy {
    val core = File("../core/src/main/res/values/strings.xml")
    check(core.exists()) { "the texts of core are not at ${core.absolutePath}, the tests must run in the directory of a module of android/" }
    listOf(core, File("src/main/res/values/strings.xml")).filter { it.exists() }.fold(emptyMap()) { all, file ->
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        all +
            (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'").replace("\\\"", "\"")
            }
    }
}

private fun nameOf(id: Int): String =
    listOf("$appPackage.R\$string", "io.pelle.sak.core.R\$string")
        .asSequence()
        .mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
        .flatMap { it.fields.asSequence() }
        .first { it.getInt(null) == id }
        .name

fun UiText?.english(): String? =
    when (this) {
        null -> null
        is UiText.Plain -> text
        // the format of android for an english phone, not the one of the machine of the test
        is UiText.Res -> String.format(Locale.ROOT, strings.getValue(nameOf(id)), *args.toTypedArray())
    }
