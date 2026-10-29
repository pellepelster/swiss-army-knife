//! the commands of the pages about the texts

use std::collections::HashMap;

// the language of the page, `<html lang>`
#[tauri::command]
pub fn locale() -> String {
    rust_i18n::locale().to_string()
}

// the texts of the page: below `ui.` (the window alone) and `shared.` (the window and the command
// line) in locales/, english for the ones missing in the language
#[tauri::command]
pub fn ui_texts() -> HashMap<String, String> {
    crate::_rust_i18n_backend()
        .messages_for_locale("en")
        .unwrap_or_default()
        .into_iter()
        .filter(|(key, _)| key.starts_with("ui.") || key.starts_with("shared."))
        .map(|(key, _)| {
            let text = rust_i18n::t!(key.as_ref()).to_string();
            (key.to_string(), text)
        })
        .collect()
}
