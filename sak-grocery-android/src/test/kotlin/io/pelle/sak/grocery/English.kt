package io.pelle.sak.grocery

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// the english texts of the app, res/values/strings.xml as android reads it, by their names
private val strings: Map<String, String> by lazy {
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
    val nodes = document.getElementsByTagName("string")
    (0 until nodes.length).associate { index ->
        val node = nodes.item(index)
        node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'").replace("\\\"", "\"")
    }
}

// the name of a string resource, e.g. error_session_ended
private fun nameOf(id: Int): String =
    R.string::class.java.fields.first { it.getInt(null) == id }.name

// a text as the user of a phone in english reads it
fun UiText?.english(): String? =
    when (this) {
        null -> null
        is UiText.Plain -> text
        is UiText.Res -> String.format(strings.getValue(nameOf(id)), *args.toTypedArray())
    }
