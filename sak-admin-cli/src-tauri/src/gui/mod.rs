//! the window: tauri with the pages of ui/, their commands and the checks of the splash screen.
//! The commands call into core, which does not know tauri

pub mod checks;
pub mod deployments;
pub mod i18n;
pub mod maintenance;
pub mod providers;
pub mod state;
pub mod update;

use crate::core::deployments::DeploymentManager;
use crate::core::providers::ProviderTokens;

/// the entry of sakadmin in the desktop of linux
#[cfg(target_os = "linux")]
const DESKTOP_ENTRY: sak_desktop_shared::desktop::DesktopEntry =
    sak_desktop_shared::desktop::DesktopEntry {
        app_id: "sakadmin",
        name: "Swiss Army Knife",
        comment: "Create, update and back up your Swiss Army Knife",
        icon: include_bytes!("../../../../sak-theme/images/tauri/icon.png"),
    };

/// runs the window until it closes; then a provisioning that runs stops and its files go
pub fn run() {
    crate::core::provision::stop_runs_on_signal();
    // the icon of the window in the taskbar and the dock
    #[cfg(target_os = "linux")]
    DESKTOP_ENTRY.install();
    tauri::Builder::default()
        .manage(crate::core::container::ContainerRuntime::default())
        .manage(ProviderTokens::default())
        .manage(DeploymentManager)
        .manage(deployments::FrontendUrls::default())
        .manage(state::RunningWork::default())
        // what the commands of the update compare and replace (sak_desktop_shared::update)
        .manage(crate::core::APP)
        .manage(state::CheckedDeployment::default())
        .manage(state::LoadedSettings::default())
        .plugin(tauri_plugin_opener::init())
        // closed while a create, deploy, destroy or maintenance runs, the window stays and the page
        // asks (app.js); `quit_working` quits after a yes (DA-29)
        .on_window_event(|window, event| {
            use tauri::{Emitter, Manager};
            if let tauri::WindowEvent::CloseRequested { api, .. } = event
                && window.state::<state::RunningWork>().is_running()
            {
                api.prevent_close();
                let _ = window.emit("close-requested", ());
            }
        })
        .invoke_handler(tauri::generate_handler![
            checks::run_checks,
            sak_desktop_shared::checks::quit,
            i18n::locale,
            i18n::ui_texts,
            providers::list_provider_types,
            providers::list_provider_tokens,
            providers::add_provider_token,
            deployments::list_deployments,
            deployments::deployment_defaults,
            deployments::check_name,
            deployments::send_test_mail,
            deployments::locations,
            deployments::open_url,
            deployments::root_domains,
            deployments::plan_deployment,
            deployments::deployment_settings,
            deployments::check_deployment,
            deployments::apply_checked,
            deployments::cancel_apply,
            deployments::quit_working,
            deployments::check_deployment_password,
            maintenance::run_maintenance,
            sak_desktop_shared::update::version,
            sak_desktop_shared::update::check_update,
            sak_desktop_shared::update::install_update,
            update::restart
        ])
        .build(tauri::generate_context!())
        .expect("sakadmin failed to start")
        .run(|_, event| {
            if let tauri::RunEvent::Exit = event {
                crate::core::provision::stop_runs();
            }
        });
}

#[cfg(test)]
mod tests {
    // every invoke of the page names a command of the window with its arguments. sakadmin has no
    // sign-in, so its page does not load shared/login.js
    sak_desktop_shared::command_tests!(
        handler: include_str!("mod.rs"),
        sources: |module| match module {
            "checks" => include_str!("checks.rs"),
            "deployments" => include_str!("deployments.rs"),
            "i18n" => include_str!("i18n.rs"),
            "maintenance" => include_str!("maintenance.rs"),
            "providers" => include_str!("providers.rs"),
            "update" => include_str!("update.rs"),
            other => panic!("the test knows no source of the module {other}"),
        },
        ui: concat!(env!("CARGO_MANIFEST_DIR"), "/../ui"),
        unused: ["login.js"],
        invokes: 20,
    );

    /// every color of the page is a token of sak-theme; the stylesheet has no color of its own
    #[test]
    fn the_stylesheet_has_no_color_of_its_own() {
        let css = include_str!("../../../ui/style.css");
        let literal = css
            .lines()
            .find(|line| line.contains('#') || line.contains("rgb(") || line.contains("rgba("));
        assert_eq!(
            literal, None,
            "a color literal in ui/style.css, use a var(--sak-…) of sak-theme"
        );
        assert!(css.contains("var(--sak-accent)"));
    }

    /// every button that creates or adds something starts with the plus sign of sak-theme
    #[test]
    fn every_create_button_starts_with_the_plus_sign() {
        let html = include_str!("../../../ui/index.html");
        let mut creates = 0;
        for button in html.split("<button").skip(1) {
            let button = button.split("</button>").next().unwrap_or_default();
            let creating = ["ui.token.add", "ui.token.add", "ui.deployments.create"]
                .iter()
                .any(|key| button.contains(key));
            if creating {
                creates += 1;
                assert!(
                    button.contains("sak-icon-plus"),
                    "a create button without the plus sign: {button}"
                );
            }
        }
        assert_eq!(creates, 3);
    }
}
