//! the window: tauri with the pages of ui/, their commands and the checks of the splash
//! screen; the commands call into core, this is where tauri lives

pub mod checks;
pub mod deployments;
pub mod i18n;
pub mod providers;

use crate::core::deployments::DeploymentManager;
use crate::core::providers::ProviderTokens;

/// runs the window until it is closed
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(crate::core::container::ContainerRuntime::default())
        .manage(ProviderTokens::default())
        .manage(DeploymentManager)
        .invoke_handler(tauri::generate_handler![
            checks::run_checks,
            i18n::locale,
            i18n::ui_texts,
            providers::list_provider_types,
            providers::list_provider_tokens,
            providers::add_provider_token,
            deployments::list_deployments,
            deployments::deployment_defaults,
            deployments::locations,
            deployments::root_domains,
            deployments::plan_deployment,
            deployments::create_deployment
        ])
        .run(tauri::generate_context!())
        .expect("sakctl failed to start");
}

#[cfg(test)]
mod tests {
    /// every color of the page is a token of sak-theme, the stylesheet names none itself
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
            let creating = [
                "ui.providers.add",
                "ui.token.add",
                "ui.deployments.create",
                "ui.plan.apply",
            ]
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
        assert_eq!(creates, 4);
    }
}
