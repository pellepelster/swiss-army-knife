/// sets the language of the texts: the one of the system when there are texts for it, else english
pub fn init() {
    rust_i18n::set_locale(&select_locale(sys_locale::get_locale()));
}

// "de-DE" (macos, windows) or "de_DE.UTF-8" (linux) is "de", unless there are texts for the region
fn select_locale(system: Option<String>) -> String {
    let available = rust_i18n::available_locales!();
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
        .find(|candidate| available.iter().any(|locale| locale == candidate))
        .unwrap_or("en")
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_i18n::t;
    use std::collections::BTreeSet;

    fn keys(locale: &str) -> BTreeSet<String> {
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
        assert!(!english.is_empty());
        for locale in rust_i18n::available_locales!() {
            assert_eq!(keys(&locale), english, "the texts of {locale}");
        }
    }

    /// a text of another language fills the same values as the english one, no more and no less
    #[test]
    fn every_text_has_the_placeholders_of_english() {
        let placeholders = |text: &str| -> BTreeSet<String> {
            text.split("%{")
                .skip(1)
                .filter_map(|rest| rest.split_once('}').map(|(name, _)| name.to_string()))
                .collect()
        };
        let texts = |locale: &str| -> std::collections::BTreeMap<String, String> {
            crate::_rust_i18n_backend()
                .messages_for_locale(locale)
                .unwrap_or_default()
                .into_iter()
                .map(|(key, text)| (key.to_string(), text.to_string()))
                .collect()
        };
        let english = texts("en");
        for locale in rust_i18n::available_locales!() {
            for (key, text) in texts(&locale) {
                assert_eq!(
                    placeholders(&text),
                    placeholders(&english[&key]),
                    "{key} in {locale}"
                );
            }
        }
    }

    #[test]
    fn the_language_of_the_system_is_selected() {
        assert_eq!(select_locale(Some("de-DE".to_string())), "de");
        assert_eq!(select_locale(Some("de_AT.UTF-8".to_string())), "de");
        assert_eq!(select_locale(Some("en-US".to_string())), "en");
    }

    #[test]
    fn english_is_selected_without_texts_for_the_system() {
        assert_eq!(select_locale(Some("fr-FR".to_string())), "en");
        assert_eq!(select_locale(None), "en");
    }

    #[test]
    fn the_texts_follow_the_locale() {
        assert_eq!(t!("ui.quit", locale = "de"), "Beenden");
        assert_eq!(t!("ui.quit", locale = "en"), "Quit");
    }
}
