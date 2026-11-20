//! the window: tauri with the pages of ui/ and their commands. The sign-in and the session are the
//! ones of sak-desktop-shared, the session in memory only (SECURITY-3)

use rust_i18n::t;
use sak_desktop_shared::session::SessionManager;
use std::collections::HashMap;
use tauri::Manager;

/// the entry of sak-backup in the desktop of linux
#[cfg(target_os = "linux")]
const DESKTOP_ENTRY: sak_desktop_shared::desktop::DesktopEntry =
    sak_desktop_shared::desktop::DesktopEntry {
        app_id: "sak-backup",
        name: "SAK Backup",
        comment: "The backups of your Swiss Army Knife",
        icon: include_bytes!("../../../sak-theme/images/tauri/icon.png"),
    };

/// runs the window until it closes
pub fn run() {
    #[cfg(target_os = "linux")]
    DESKTOP_ENTRY.install();
    tauri::Builder::default()
        .manage(SessionManager::default())
        .setup(|app| {
            app.manage(sak_desktop_shared::window::instance_store(app)?);
            Ok(())
        })
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            locale,
            ui_texts,
            sak_desktop_shared::window::user,
            sak_desktop_shared::window::known_instances,
            sak_desktop_shared::window::login,
            sak_desktop_shared::window::cancel_login,
            sak_desktop_shared::window::logout
        ])
        .run(tauri::generate_context!())
        .expect("the window of sak-backup");
}

// the language of the page, `<html lang>`
#[tauri::command]
fn locale() -> String {
    rust_i18n::locale().to_string()
}

// the texts of the page: its own below `ui.` and `common.` in locales/, and the ones of
// sak-desktop-shared (the sign-in); english for the ones missing in the language
#[tauri::command]
fn ui_texts() -> HashMap<String, String> {
    let keys = crate::_rust_i18n_backend()
        .messages_for_locale("en")
        .unwrap_or_default()
        .into_iter()
        .map(|(key, _)| key.to_string());
    let mut texts = sak_desktop_shared::i18n::window_texts(keys, &["ui.", "common."], |key| {
        t!(key).to_string()
    });
    texts.extend(sak_desktop_shared::i18n::shared_texts());
    texts
}

#[cfg(test)]
mod tests {
    use super::*;
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

    #[test]
    fn the_window_has_its_texts_and_the_sign_in() {
        let texts = ui_texts();
        assert_eq!(texts["ui.title"], "SAK Backup");
        assert!(texts.contains_key("ui.login.sign_in"));
        // the keys of the app and of the shared sign-in do not overlap
        let shared = sak_desktop_shared::i18n::shared_texts();
        assert!(keys("en").iter().all(|key| !shared.contains_key(key)));
    }
}
