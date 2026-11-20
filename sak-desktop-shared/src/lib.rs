//! what the desktop apps of SAK (sakadm, sak-backup) share: the sign-in through the browser with
//! its window (ui/login.js and the commands of `window`), the language and the texts, the desktop
//! entry. Each app keeps its own texts too (rust_i18n is per crate)

// the texts of locales/, generated from `desktop_shared` of i18n/texts.yml
rust_i18n::i18n!("locales", fallback = "en");

pub mod desktop;
pub mod i18n;
pub mod session;
pub mod window;
