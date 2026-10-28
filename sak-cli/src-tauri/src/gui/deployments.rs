//! the commands of the pages about deployments, each a thin call into core

use crate::core::container::ContainerRuntime;
use crate::core::deployments::{
    CreateEvent, DeploymentDefaults, DeploymentManager, DeploymentResponse, LocationResponse,
    NewDeployment, PlanResponse, RootDomainResponse, defaults,
};
use crate::core::error::WizardError;
use crate::core::providers::ProviderTokens;
use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;

#[tauri::command]
pub fn deployment_defaults() -> DeploymentDefaults {
    defaults()
}

/// opens the frontend of a deployment in the browser of the system; nothing but an https url, the
/// page gets no opener of its own
#[tauri::command]
pub fn open_url(app: AppHandle, url: String) -> Result<(), String> {
    if !is_https(&url) {
        return Err(format!("'{url}' is no https url"));
    }
    app.opener()
        .open_url(url, None::<&str>)
        .map_err(|error| error.to_string())
}

/// `https://` and a host, no other scheme reaches the browser
fn is_https(url: &str) -> bool {
    url.strip_prefix("https://")
        .is_some_and(|rest| !rest.is_empty() && !rest.contains(char::is_whitespace))
}

#[tauri::command]
pub fn locations() -> Vec<LocationResponse> {
    crate::core::deployments::locations()
}

#[tauri::command]
pub async fn root_domains(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    name: String,
) -> Result<Vec<RootDomainResponse>, String> {
    let tokens = tokens.tokens();
    manager.root_domains(tokens, &name).await
}

#[tauri::command]
pub async fn plan_deployment(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    deployment: NewDeployment,
) -> Result<PlanResponse, WizardError> {
    let tokens = tokens.tokens();
    manager.plan(tokens, &deployment).await
}

#[tauri::command]
pub async fn create_deployment(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    container: State<'_, ContainerRuntime>,
    deployment: NewDeployment,
    on_event: Channel<CreateEvent>,
) -> Result<(), WizardError> {
    let tokens = tokens.tokens();
    // a progress event that cannot be delivered is no reason to stop creating
    manager
        .create(tokens, &deployment, &container, &mut |event| {
            let _ = on_event.send(event);
        })
        .await
}

#[tauri::command]
pub async fn list_deployments(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
) -> Result<Vec<DeploymentResponse>, String> {
    let tokens = tokens.tokens();
    manager.list(tokens).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_https_urls_reach_the_browser() {
        assert!(is_https("https://sak.alpha.pelle.io"));
        for url in [
            "http://sak.pelle.io",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "https://",
            "https://a b",
            "",
        ] {
            assert!(!is_https(url), "{url}");
        }
    }
}
