//! what the desktop apps of SAK (sakadmin, sakbackup) share: the sign-in through the browser with
//! its window (ui/login.js and the commands of `window`), the instances it knows, the language and
//! the texts, the desktop entry. Each app keeps its own texts too (rust_i18n is per crate)

// the texts of locales/, generated from `desktop_shared` of i18n/texts.yml
rust_i18n::i18n!("locales", fallback = "en");

pub mod api;
pub mod checks;
pub mod cli;
pub mod command_tests;
pub mod desktop;
pub mod encoding;
pub mod errors;
pub mod i18n;
pub mod instances;
pub mod secrets;
pub mod session;
pub mod sigstore;
pub mod update;
pub mod window;

/// the version of SAK the app is built as (`VERSION` of the build, build.rs): the tag of a release
/// without its v, `latest` for a build that is no release
pub const VERSION: &str = env!("SAK_VERSION");
