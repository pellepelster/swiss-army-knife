// no console window next to the app in windows release builds
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

// the texts of locales/, english for the ones missing in a language
rust_i18n::i18n!("locales", fallback = "en");

mod checks;
mod container;
mod deployments;
mod i18n;
mod keys;
mod providers;

fn main() {
    i18n::init();
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(container::ContainerRuntime::default())
        .manage(providers::ProviderTokens::default())
        .manage(deployments::DeploymentManager)
        .invoke_handler(tauri::generate_handler![
            checks::run_checks,
            i18n::locale,
            i18n::ui_texts,
            providers::list_provider_types,
            providers::list_provider_tokens,
            providers::add_provider_token,
            deployments::list_deployments,
            deployments::plan_deployment
        ])
        .run(tauri::generate_context!())
        .expect("sak-wizard failed to start");
}

#[cfg(test)]
mod tests {
    /// every color of the page is a token of sak-theme, the stylesheet names none itself
    #[test]
    fn the_stylesheet_has_no_color_of_its_own() {
        let css = include_str!("../../ui/style.css");
        let literal = css
            .lines()
            .find(|line| line.contains('#') || line.contains("rgb(") || line.contains("rgba("));
        assert_eq!(
            literal, None,
            "a color literal in ui/style.css, use a var(--sak-…) of sak-theme"
        );
        assert!(css.contains("var(--sak-accent)"));
    }
}
