// no console window next to the app in windows release builds
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

// the texts of locales/, english for the ones missing in a language
rust_i18n::i18n!("locales", fallback = "en");

mod checks;
mod i18n;

fn main() {
    i18n::init();
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![checks::run_checks, i18n::locale])
        .run(tauri::generate_context!())
        .expect("sak-wizard failed to start");
}
