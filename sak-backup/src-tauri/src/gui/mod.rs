//! the window: tauri with the pages of ui/ and their commands (commands.rs, on core/). The sign-in
//! and the session are the ones of sak-desktop-shared, the session token in the keychain of the
//! system (SECURITY-4)

use tauri::Manager;

mod commands;
mod tray;

/// the entry of sakbackup in the desktop of linux
#[cfg(target_os = "linux")]
const DESKTOP_ENTRY: sak_desktop_shared::desktop::DesktopEntry =
    sak_desktop_shared::desktop::DesktopEntry {
        app_id: "sakbackup",
        name: "SAK Backup",
        comment: "The backups of your Swiss Army Knife",
        icon: include_bytes!("../../../../sak-theme/images/sakbackup/icon.png"),
    };

/// runs the window until it closes
pub fn run() {
    #[cfg(target_os = "linux")]
    DESKTOP_ENTRY.install();
    tauri::Builder::default()
        .manage(crate::core::run::Runs::default())
        .manage(crate::core::passwords::PasswordStore::default())
        // what the commands of the update compare and replace (sak_desktop_shared::update)
        .manage(crate::core::APP)
        .setup(|app| {
            app.manage(sak_desktop_shared::window::session_manager(app));
            app.manage(sak_desktop_shared::window::instance_store(app)?);
            // a system without a tray has the window only
            if let Err(error) = tray::create(app.handle()) {
                eprintln!("the icon in the tray was not created: {error}");
            }
            Ok(())
        })
        // closed while something runs, the window goes to the tray (tray.rs)
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                tray::close_requested(window, api);
            }
        })
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            locale,
            ui_texts,
            sak_desktop_shared::window::restore_session,
            sak_desktop_shared::window::known_instances,
            sak_desktop_shared::window::login,
            sak_desktop_shared::window::cancel_login,
            sak_desktop_shared::window::logout,
            sak_desktop_shared::update::version,
            sak_desktop_shared::update::check_update,
            sak_desktop_shared::update::install_update,
            commands::restart,
            commands::run_checks,
            sak_desktop_shared::checks::quit,
            commands::host,
            commands::repositories,
            commands::create_repository,
            commands::plans,
            commands::create_plan,
            commands::update_plan,
            commands::delete_plan,
            commands::delete_repository,
            commands::exclude_presets,
            commands::read_excludes,
            commands::pick_paths,
            commands::pick_folder,
            commands::run_backup,
            commands::cancel_backup,
            commands::restore_backup,
            commands::snapshots,
            commands::delete_snapshot,
            commands::env_password_set,
            commands::password_kept,
            commands::forget_password
        ])
        .run(tauri::generate_context!())
        .expect("the window of sakbackup");
}

// the language and the texts of the page: its own below `ui.` and `common.` in locales/, and the
// ones of sak-desktop-shared (the sign-in, the update); english for the ones missing in the language
sak_desktop_shared::ui_text_commands!(shared: true);

#[cfg(test)]
mod window_fixtures;

#[cfg(test)]
mod tests {
    use super::*;

    sak_desktop_shared::locale_tests!();

    #[test]
    fn the_window_has_its_texts_and_the_sign_in() {
        let texts = ui_texts();
        assert_eq!(texts["ui.title"], "SAK Backup");
        assert!(texts.contains_key("ui.login.sign_in"));
        // the keys of the app and of the shared sign-in do not overlap
        let shared = sak_desktop_shared::i18n::shared_texts();
        let own = sak_desktop_shared::i18n::keys(crate::_rust_i18n_backend(), "en");
        assert!(own.iter().all(|key| !shared.contains_key(key)));
    }

    // every invoke of the page names a command of the window with its arguments: the ones of
    // commands.rs, `locale` and `ui_texts` of this file, and the ones of sak-desktop-shared
    sak_desktop_shared::command_tests!(
        handler: include_str!("mod.rs"),
        sources: |module| match module {
            "" => include_str!("mod.rs"),
            "commands" => include_str!("commands.rs"),
            "tray" => include_str!("tray.rs"),
            other => panic!("the test knows no source of the module {other}"),
        },
        ui: concat!(env!("CARGO_MANIFEST_DIR"), "/../ui"),
        unused: [],
        invokes: 20,
    );
}
