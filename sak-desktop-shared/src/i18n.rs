//! the language of a desktop app and the texts its window reads; the texts are the app's own
//! (rust_i18n of its crate), handed in as functions

use std::collections::HashMap;

/// the locale of the system when `available` has texts for it, its language when only that has
/// texts, else english. "de-DE" (macos, windows) and "de_DE.UTF-8" (linux) are both "de-DE"
pub fn select_locale<S: AsRef<str>>(system: Option<String>, available: &[S]) -> String {
    let Some(system) = system else {
        return "en".to_string();
    };
    let system = system
        .split('.')
        .next()
        .unwrap_or_default()
        .replace('_', "-");
    let language = system.split('-').next().unwrap_or_default();
    [system.as_str(), language]
        .into_iter()
        .find(|candidate| available.iter().any(|locale| locale.as_ref() == *candidate))
        .unwrap_or("en")
        .to_string()
}

/// the texts of the window: each of `keys` below one of `prefixes`, translated into the language
/// of the app by `translate`
pub fn window_texts(
    keys: impl IntoIterator<Item = String>,
    prefixes: &[&str],
    translate: impl Fn(&str) -> String,
) -> HashMap<String, String> {
    keys.into_iter()
        .filter(|key| prefixes.iter().any(|prefix| key.starts_with(prefix)))
        .map(|key| {
            let text = translate(&key);
            (key, text)
        })
        .collect()
}

/// the texts of the window this crate has (below `ui.`, e.g. the sign-in), in the language the
/// app set; an app adds them to its own
pub fn shared_texts() -> HashMap<String, String> {
    let keys = crate::_rust_i18n_backend()
        .messages_for_locale("en")
        .unwrap_or_default()
        .into_iter()
        .map(|(key, _)| key.to_string());
    window_texts(keys, &["ui."], |key| rust_i18n::t!(key).to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    const AVAILABLE: [&str; 2] = ["en", "de"];

    #[test]
    fn the_language_of_the_system_is_selected() {
        assert_eq!(select_locale(Some("de-DE".to_string()), &AVAILABLE), "de");
        assert_eq!(
            select_locale(Some("de_AT.UTF-8".to_string()), &AVAILABLE),
            "de"
        );
        assert_eq!(select_locale(Some("en-US".to_string()), &AVAILABLE), "en");
    }

    #[test]
    fn english_is_selected_without_texts_for_the_system() {
        assert_eq!(select_locale(Some("fr-FR".to_string()), &AVAILABLE), "en");
        assert_eq!(select_locale(None, &AVAILABLE), "en");
    }

    #[test]
    fn the_window_gets_the_texts_of_its_prefixes_only() {
        let keys = ["ui.quit", "common.done", "cli.hint"].map(String::from);
        let texts = window_texts(keys, &["ui.", "common."], |key| key.to_uppercase());
        assert_eq!(texts.len(), 2);
        assert_eq!(texts["ui.quit"], "UI.QUIT");
        assert!(!texts.contains_key("cli.hint"));
    }

    fn keys(locale: &str) -> std::collections::BTreeSet<String> {
        crate::_rust_i18n_backend()
            .messages_for_locale(locale)
            .unwrap_or_default()
            .into_iter()
            .map(|(key, _)| key.to_string())
            .collect()
    }

    #[test]
    fn every_locale_has_the_texts_of_english() {
        let english = keys("en");
        assert!(english.contains("ui.login.sign_in"));
        for locale in rust_i18n::available_locales!() {
            assert_eq!(keys(&locale), english, "the texts of {locale}");
        }
    }
}
