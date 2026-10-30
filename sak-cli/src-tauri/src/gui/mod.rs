//! the window: tauri with the pages of ui/, their commands and the checks of the splash screen.
//! The commands call into core, which does not know tauri

pub mod checks;
pub mod deployments;
pub mod i18n;
pub mod providers;
pub mod update;

use crate::core::deployments::DeploymentManager;
use crate::core::providers::ProviderTokens;

/// runs the window until it closes; then a provisioning that runs stops and its files go
pub fn run() {
    crate::core::provision::stop_runs_on_signal();
    tauri::Builder::default()
        .manage(crate::core::container::ContainerRuntime::default())
        .manage(ProviderTokens::default())
        .manage(DeploymentManager)
        .manage(deployments::FrontendUrls::default())
        .manage(deployments::RunningApply::default())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            checks::run_checks,
            checks::quit,
            i18n::locale,
            i18n::ui_texts,
            providers::list_provider_types,
            providers::list_provider_tokens,
            providers::add_provider_token,
            deployments::list_deployments,
            deployments::deployment_defaults,
            deployments::check_name,
            deployments::detected_settings,
            deployments::locations,
            deployments::open_url,
            deployments::root_domains,
            deployments::plan_deployment,
            deployments::create_deployment,
            deployments::cancel_apply,
            deployments::check_deployment_password,
            update::version,
            update::check_update,
            update::install_update
        ])
        .build(tauri::generate_context!())
        .expect("sakctl failed to start")
        .run(|_, event| {
            if let tauri::RunEvent::Exit = event {
                crate::core::provision::stop_runs();
            }
        });
}

#[cfg(test)]
mod tests {
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
            let creating = ["ui.providers.add", "ui.token.add", "ui.deployments.create"]
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
