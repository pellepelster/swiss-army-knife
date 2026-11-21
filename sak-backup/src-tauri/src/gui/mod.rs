//! the window: tauri with the pages of ui/ and their commands (commands.rs, on core/). The sign-in
//! and the session are the ones of sak-desktop-shared, the session token in the keychain of the
//! system (SECURITY-3)

use tauri::Manager;

mod commands;

/// the entry of sakbackup in the desktop of linux
#[cfg(target_os = "linux")]
const DESKTOP_ENTRY: sak_desktop_shared::desktop::DesktopEntry =
    sak_desktop_shared::desktop::DesktopEntry {
        app_id: "sakbackup",
        name: "SAK Backup",
        comment: "The backups of your Swiss Army Knife",
        icon: include_bytes!("../../../../sak-theme/images/tauri/icon.png"),
    };

/// runs the window until it closes
pub fn run() {
    #[cfg(target_os = "linux")]
    DESKTOP_ENTRY.install();
    tauri::Builder::default()
        .manage(crate::core::run::Runs::default())
        .manage(crate::core::passwords::PasswordStore::default())
        .setup(|app| {
            app.manage(sak_desktop_shared::window::session_manager(app));
            app.manage(sak_desktop_shared::window::instance_store(app)?);
            Ok(())
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
            commands::host,
            commands::repositories,
            commands::create_repository,
            commands::plans,
            commands::create_plan,
            commands::update_plan,
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
// ones of sak-desktop-shared (the sign-in); english for the ones missing in the language
sak_desktop_shared::ui_text_commands!(shared: true);

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

    /// the commands the window registers, by name, with the arguments of the page for the ones of
    /// commands.rs (snake case); None for the shared ones, whose arguments sak-desktop-shared checks
    fn registered() -> std::collections::BTreeMap<String, Option<std::collections::BTreeSet<String>>>
    {
        let source = include_str!("mod.rs");
        let list = source
            .split("generate_handler![")
            .nth(1)
            .unwrap()
            .split(']')
            .next()
            .unwrap();
        let commands = include_str!("commands.rs");
        list.split(',')
            .map(|entry| entry.trim())
            .filter(|entry| !entry.is_empty())
            .map(|entry| {
                let name = entry.rsplit("::").next().unwrap().to_string();
                let arguments = entry.starts_with("commands::").then(|| {
                    let after = commands
                        .split(&format!("fn {name}("))
                        .nth(1)
                        .unwrap_or_else(|| panic!("{name} is not in commands.rs"));
                    // up to the parenthesis that closes the parameters
                    let mut depth = 0;
                    let end = after
                        .char_indices()
                        .find(|(_, c)| {
                            match c {
                                '(' | '<' => depth += 1,
                                ')' | '>' if depth > 0 => depth -= 1,
                                ')' => return true,
                                _ => {}
                            }
                            false
                        })
                        .unwrap()
                        .0;
                    let signature = &after[..end];
                    signature
                        .split(',')
                        .filter_map(|parameter| parameter.split_once(':'))
                        .filter(|(_, kind)| !kind.contains("State<") && !kind.contains("AppHandle"))
                        .map(|(argument, _)| argument.trim().to_string())
                        .collect()
                });
                (name, arguments)
            })
            .collect()
    }

    /// every `invoke` of the page: the command and the names of its arguments, in snake case
    fn invoked() -> Vec<(String, String, std::collections::BTreeSet<String>)> {
        let ui = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../ui");
        let mut files: Vec<_> = std::fs::read_dir(&ui)
            .unwrap()
            .chain(std::fs::read_dir(ui.join("shared")).unwrap())
            .map(|entry| entry.unwrap().path())
            .filter(|path| path.extension().is_some_and(|extension| extension == "js"))
            .collect();
        files.sort();
        let snake = |name: &str| {
            name.chars()
                .flat_map(|c| {
                    if c.is_ascii_uppercase() {
                        vec!['_', c.to_ascii_lowercase()]
                    } else {
                        vec![c]
                    }
                })
                .collect::<String>()
        };
        let mut calls = Vec::new();
        for file in files {
            let source = std::fs::read_to_string(&file).unwrap();
            for call in source.split("invoke('").skip(1) {
                let (name, rest) = call.split_once('\'').unwrap();
                let arguments = rest
                    .trim_start()
                    .strip_prefix(',')
                    .and_then(|rest| rest.trim_start().strip_prefix('{'))
                    .map(|rest| {
                        rest.split('}')
                            .next()
                            .unwrap()
                            .split(',')
                            .map(|argument| argument.split(':').next().unwrap().trim())
                            .filter(|argument| !argument.is_empty())
                            .map(snake)
                            .collect()
                    })
                    .unwrap_or_default();
                let file = file.file_name().unwrap().to_string_lossy().into_owned();
                calls.push((file, name.to_string(), arguments));
            }
        }
        calls
    }

    #[test]
    fn every_invoke_of_the_page_names_a_command_with_its_arguments() {
        let registered = registered();
        let calls = invoked();
        assert!(calls.len() > 20, "{calls:?}");
        for (file, name, arguments) in &calls {
            let command = registered
                .get(name)
                .unwrap_or_else(|| panic!("{file} invokes {name}, no command of the window"));
            if let Some(expected) = command {
                assert_eq!(arguments, expected, "{file} invokes {name}");
            }
        }
    }
}
