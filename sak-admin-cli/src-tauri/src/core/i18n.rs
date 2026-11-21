use rust_i18n::t;
use std::sync::OnceLock;

/// which of the two sakadmin is: the command line or the window. A text of core that tells the user
/// what to do next says it the way of the one that runs (`hint`)
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Frontend {
    CommandLine,
    Window,
}

static FRONTEND: OnceLock<Frontend> = OnceLock::new();

/// sets the frontend, once, when sakadmin starts; without, the command line
pub fn set_frontend(frontend: Frontend) {
    let _ = FRONTEND.set(frontend);
}

/// what the user gives when a deploy lacks it, each said by the frontend in its own way
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Hint {
    UserMode,
    SmtpHost,
    AdminEmail,
    AdminEmailSingle,
    RootDomain,
    SmtpPassword,
    SmtpFrom,
}

/// how the user gives what `hint` names, in the frontend that runs: an argument or a variable on
/// the command line, a field of the form in the window, or the command line where the window cannot
pub fn hint(hint: Hint) -> String {
    let frontend = FRONTEND.get().copied().unwrap_or(Frontend::CommandLine);
    let name = match hint {
        Hint::UserMode => "user_mode",
        Hint::SmtpHost => "smtp_host",
        Hint::AdminEmail => "admin_email",
        Hint::AdminEmailSingle => "admin_email_single",
        Hint::RootDomain => "root_domain",
        Hint::SmtpPassword => "smtp_password",
        Hint::SmtpFrom => "smtp_from",
    };
    match frontend {
        Frontend::CommandLine => t!(format!("cli.hint.{name}")).to_string(),
        Frontend::Window => t!(format!("ui.hint.{name}")).to_string(),
    }
}

/// sets the language of the texts: the one of the system when there are texts for it, else english
pub fn init() {
    sak_desktop_shared::i18n::init_locale(&rust_i18n::available_locales!());
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_i18n::t;

    sak_desktop_shared::locale_tests!();

    fn keys(locale: &str) -> std::collections::BTreeSet<String> {
        sak_desktop_shared::i18n::keys(crate::_rust_i18n_backend(), locale)
    }

    #[test]
    fn a_hint_has_a_text_for_the_command_line_and_the_window() {
        for hint in [
            Hint::UserMode,
            Hint::SmtpHost,
            Hint::AdminEmail,
            Hint::RootDomain,
            Hint::SmtpPassword,
            Hint::SmtpFrom,
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
    fn the_texts_follow_the_locale() {
        assert_eq!(t!("ui.quit", locale = "de"), "Beenden");
        assert_eq!(t!("ui.quit", locale = "en"), "Quit");
    }
}
