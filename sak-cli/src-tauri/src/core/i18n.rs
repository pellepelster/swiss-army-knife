use rust_i18n::t;
use std::sync::OnceLock;

/// which of the two sakctl is: the command line or the window. A text of core that tells the user
/// what to do next says it the way of the one that runs (`hint`)
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Frontend {
    CommandLine,
    Window,
}

static FRONTEND: OnceLock<Frontend> = OnceLock::new();

/// sets the frontend, once, when sakctl starts; without, the command line
pub fn set_frontend(frontend: Frontend) {
    let _ = FRONTEND.set(frontend);
}

/// what the user gives when a deploy lacks it, each said by the frontend in its own way
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Hint {
    UserMode,
    SmtpHost,
    AdminEmail,
    RootDomain,
    SmtpPassword,
}

/// how the user gives what `hint` names, in the frontend that runs: an argument or a variable on
/// the command line, a field of the form in the window, or the command line where the window cannot
pub fn hint(hint: Hint) -> String {
    let frontend = FRONTEND.get().copied().unwrap_or(Frontend::CommandLine);
    let name = match hint {
        Hint::UserMode => "user_mode",
        Hint::SmtpHost => "smtp_host",
        Hint::AdminEmail => "admin_email",
        Hint::RootDomain => "root_domain",
        Hint::SmtpPassword => "smtp_password",
    };
    match frontend {
        Frontend::CommandLine => t!(format!("cli.hint.{name}")).to_string(),
        Frontend::Window => t!(format!("ui.hint.{name}")).to_string(),
    }
}

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
    fn a_hint_has_a_text_for_the_command_line_and_the_window() {
        for hint in [
            Hint::UserMode,
            Hint::SmtpHost,
            Hint::AdminEmail,
            Hint::RootDomain,
            Hint::SmtpPassword,
        ] {
            let name = format!("{hint:?}");
            let texts = keys("en");
            let snake: String = name
                .chars()
                .enumerate()
                .flat_map(|(at, c)| {
                    let lower = c.to_ascii_lowercase();
                    if c.is_uppercase() && at > 0 {
                        vec!['_', lower]
                    } else {
                        vec![lower]
                    }
                })
                .collect();
            for frontend in ["cli", "ui"] {
                let key = format!("{frontend}.hint.{snake}");
                assert!(texts.contains(&key), "{key}");
            }
        }
        // the command line names its argument, before a frontend is set
        assert!(hint(Hint::UserMode).contains("--user-mode"));
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
