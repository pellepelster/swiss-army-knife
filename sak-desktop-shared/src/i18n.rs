//! the language of a desktop app and the texts its window reads; the texts are the app's own
//! (rust_i18n of its crate), handed in as functions or read by the macros below, which an app
//! calls because the backend of rust_i18n is generated in each crate

use std::collections::{BTreeMap, BTreeSet, HashMap};

/// sets the language of the texts of every crate: the one of the system when `available` has texts
/// for it, else english (`select_locale`)
pub fn init_locale<S: AsRef<str>>(available: &[S]) {
    rust_i18n::set_locale(&select_locale(sys_locale::get_locale(), available));
}

/// the keys of the texts of a crate in a language, of its `_rust_i18n_backend()`
pub fn keys(backend: &dyn rust_i18n::Backend, locale: &str) -> BTreeSet<String> {
    texts(backend, locale).into_keys().collect()
}

/// the texts of a crate in a language by their keys, of its `_rust_i18n_backend()`
pub fn texts(backend: &dyn rust_i18n::Backend, locale: &str) -> BTreeMap<String, String> {
    backend
        .messages_for_locale(locale)
        .unwrap_or_default()
        .into_iter()
        .map(|(key, text)| (key.to_string(), text.to_string()))
        .collect()
}

/// the names of the values a text fills, `%{name}`
pub fn placeholders(text: &str) -> BTreeSet<String> {
    text.split("%{")
        .skip(1)
        .filter_map(|rest| rest.split_once('}').map(|(name, _)| name.to_string()))
        .collect()
}

/// the commands `locale` and `ui_texts` of the window of an app, in the module it is called in: the
/// language of the page (`<html lang>`) and its texts, those below `ui.` and `common.` in the
/// locales of the app, english for the ones missing in the language, with the ones of this crate
/// (the sign-in) when `shared` is true
#[macro_export]
#[allow(clippy::crate_in_macro_def)]
macro_rules! ui_text_commands {
    (shared: $shared:expr) => {
        #[tauri::command]
        pub fn locale() -> String {
            rust_i18n::locale().to_string()
        }

        #[tauri::command]
        pub fn ui_texts() -> std::collections::HashMap<String, String> {
            let keys = $crate::i18n::keys(crate::_rust_i18n_backend(), "en");
            let mut texts = $crate::i18n::window_texts(keys, &["ui.", "common."], |key| {
                rust_i18n::t!(key).to_string()
            });
            if $shared {
                texts.extend($crate::i18n::shared_texts());
            }
            texts
        }
    };
}

/// the tests of the locales of an app, in the module it is called in: every language has the texts
/// of english, each with the placeholders of the english one
#[macro_export]
#[allow(clippy::crate_in_macro_def)]
macro_rules! locale_tests {
    () => {
        #[test]
        fn every_locale_has_the_texts_of_english() {
            let english = $crate::i18n::keys(crate::_rust_i18n_backend(), "en");
            assert!(!english.is_empty());
            for locale in rust_i18n::available_locales!() {
                assert_eq!(
                    $crate::i18n::keys(crate::_rust_i18n_backend(), &locale),
                    english,
                    "the texts of {locale}"
                );
            }
        }

        #[test]
        fn every_text_has_the_placeholders_of_english() {
            let english = $crate::i18n::texts(crate::_rust_i18n_backend(), "en");
            for locale in rust_i18n::available_locales!() {
                for (key, text) in $crate::i18n::texts(crate::_rust_i18n_backend(), &locale) {
                    assert_eq!(
                        $crate::i18n::placeholders(&text),
                        $crate::i18n::placeholders(&english[&key]),
                        "{key} in {locale}"
                    );
                }
            }
        }
    };
}

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
    window_texts(keys(crate::_rust_i18n_backend(), "en"), &["ui."], |key| {
        rust_i18n::t!(key).to_string()
    })
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

    crate::locale_tests!();

    #[test]
    fn the_sign_in_has_its_texts() {
        assert!(keys(crate::_rust_i18n_backend(), "en").contains("ui.login.sign_in"));
    }

    #[test]
    fn the_placeholders_of_a_text_are_its_names() {
        assert_eq!(
            placeholders("%{files} of %{total}, %{files}"),
            BTreeSet::from(["files".to_string(), "total".to_string()])
        );
    }
}
