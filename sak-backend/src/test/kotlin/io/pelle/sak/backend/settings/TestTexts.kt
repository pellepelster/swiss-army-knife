package io.pelle.sak.backend.settings

import io.pelle.sak.api.TextKey

// a text of the tests, its english one in i18n/settingstest.en.yml: e.g. the label of a setting
fun t(english: String) = TextKey("settingstest", english.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'))
