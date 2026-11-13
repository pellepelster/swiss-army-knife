package io.pelle.sak.api.testing

import io.pelle.sak.api.Catalog
import io.pelle.sak.api.Catalogs
import io.pelle.sak.api.Language
import io.pelle.sak.api.TextKey

// what is wrong with a catalog, empty when nothing: no keys, or a key without a text in a
// language; sak-i18n checks the texts. A catalog may have more texts than keys (the common ones)
fun catalogProblems(catalog: Catalog): List<String> {
    val keys =
        catalog.javaClass.declaredFields
            .filter { it.type == TextKey::class.java }
            .map {
                it.isAccessible = true
                (it.get(catalog) as TextKey).path
            }.toSet()
    return buildList {
        if (keys.isEmpty()) add("${catalog.name} has no keys")
        for (language in Language.entries) {
            val texts = Catalogs.texts(catalog.name, language)
            (keys - texts.keys).forEach { add("${catalog.name}.${language.tag}: no text for $it") }
        }
    }
}
