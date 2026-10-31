package io.pelle.sak.model.grocery

import java.text.Normalizer
import java.util.Locale

private val WHITESPACE = Regex("\\s+")

// the identity of a product name, stored as name_key: NFKC, trimmed, whitespace collapsed, lower
// case in any script; accents stay ("Müsli" is not "Musli"). Computed here because sqlite's lower()
// folds A-Z only
internal fun groceryNameKey(name: String): String =
    Normalizer
        .normalize(name, Normalizer.Form.NFKC)
        .trim()
        .replace(WHITESPACE, " ")
        .lowercase(Locale.ROOT)
