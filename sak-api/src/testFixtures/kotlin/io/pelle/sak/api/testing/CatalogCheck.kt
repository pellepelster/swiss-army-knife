package io.pelle.sak.api.testing

import io.pelle.sak.api.Catalog
import io.pelle.sak.api.Catalogs
import io.pelle.sak.api.Language
import io.pelle.sak.api.TextKey

// what is wrong with the files of a catalog, empty when nothing is: every key of the catalog object
// has a text in every language, with the same placeholders as the english one, and there is no
// text without a key
fun catalogProblems(catalog: Catalog): List<String> {
    val keys =
        catalog.javaClass.declaredFields
            .filter { it.type == TextKey::class.java }
            .map {
                it.isAccessible = true
                (it.get(catalog) as TextKey).path
            }.toSet()
    val english = Catalogs.texts(catalog.name, Language.FALLBACK)
    return buildList {
        if (keys.isEmpty()) add("${catalog.name} has no keys")
        for (language in Language.entries) {
            val texts = Catalogs.texts(catalog.name, language)
            (keys - texts.keys).forEach { add("${catalog.name}.${language.tag}: no text for $it") }
            (texts.keys - keys).forEach { add("${catalog.name}.${language.tag}: $it is no key") }
            for ((path, text) in texts) {
                val expected = english[path]?.let(Catalogs::placeholders) ?: continue
                if (Catalogs.placeholders(text) != expected) add("${catalog.name}.${language.tag}: $path has the placeholders ${Catalogs.placeholders(text)}, not $expected")
            }
        }
    }
}
