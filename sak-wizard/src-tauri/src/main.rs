// no console window next to the app in windows release builds
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod checks;

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![checks::run_checks])
        .run(tauri::generate_context!())
        .expect("sak-wizard failed to start");
}
