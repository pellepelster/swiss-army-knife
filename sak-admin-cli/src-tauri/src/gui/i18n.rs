//! the commands of the pages about the texts

use std::collections::HashMap;

// the language of the page, `<html lang>`
#[tauri::command]
pub fn locale() -> String {
    rust_i18n::locale().to_string()
}

// the texts of the page: below `ui.` (the window alone) and `common.` (the window and the command
// line) in locales/, english for the ones missing in the language
#[tauri::command]
pub fn ui_texts() -> HashMap<String, String> {
    let keys = crate::_rust_i18n_backend()
        .messages_for_locale("en")
        .unwrap_or_default()
        .into_iter()
        .map(|(key, _)| key.to_string());
    sak_desktop_shared::i18n::window_texts(keys, &["ui.", "common."], |key| {
        rust_i18n::t!(key).to_string()
    })
}
