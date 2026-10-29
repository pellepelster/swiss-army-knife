//! the commands of the pages about deployments, each a thin call into core

use crate::core::container::ContainerRuntime;
use crate::core::deployments::{
    CreateEvent, DeploymentDefaults, DeploymentManager, DeploymentResponse, LocationResponse,
    NewDeployment, PlanResponse, RootDomainResponse, defaults,
};
use crate::core::error::WizardError;
use crate::core::providers::ProviderTokens;
use std::collections::HashSet;
use std::sync::Mutex;
use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;
use url::Url;

/// the frontend urls of the deployments the last list answered: the only urls `open_url` opens
#[derive(Default)]
pub struct FrontendUrls(Mutex<HashSet<String>>);

#[tauri::command]
pub fn deployment_defaults() -> DeploymentDefaults {
    defaults()
}

/// opens the frontend of a deployment in the browser of the system: only a url the last list of
/// deployments answered, the page gets no opener of its own
#[tauri::command]
pub fn open_url(app: AppHandle, known: State<'_, FrontendUrls>, url: String) -> Result<(), String> {
    let known = known.0.lock().map_err(|e| e.to_string())?;
    let url = checked_url(&known, &url)?;
    app.opener()
        .open_url(url.as_str(), None::<&str>)
        .map_err(|error| error.to_string())
}

/// the url when it is one of `known`, an https url with a host and no control character
fn checked_url(known: &HashSet<String>, url: &str) -> Result<Url, String> {
    let refused = || format!("'{}' is no frontend of a deployment", url.escape_debug());
    if url.chars().any(char::is_control) || !known.contains(url) {
        return Err(refused());
    }
    let parsed = Url::parse(url).map_err(|_| refused())?;
    if parsed.scheme() != "https" || parsed.host_str().is_none_or(str::is_empty) {
        return Err(refused());
    }
    Ok(parsed)
}

/// the check of a name, as the page asks it when its field is left: the message when it is none
#[tauri::command]
pub fn check_name(name: String) -> Result<(), String> {
    crate::core::deployments::checked_name(&name)
        .map(|_| ())
        .map_err(|error| error.message)
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

/// what a redeploy takes from the deployment, as the command line does: its user mode and, for
/// multiple users, the admin and the mail server
#[tauri::command]
pub async fn detected_settings(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    name: String,
    password: String,
) -> Result<crate::core::deployments::Detected, String> {
    let tokens = tokens.tokens();
    manager.detected(tokens, &name, &password).await
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
    frontend_urls: State<'_, FrontendUrls>,
) -> Result<Vec<DeploymentResponse>, String> {
    let tokens = tokens.tokens();
    let deployments = manager.list(tokens).await?;
    // what the page may open: the frontends of these deployments, no other url
    *frontend_urls.0.lock().map_err(|e| e.to_string())? = deployments
        .iter()
        .filter_map(|deployment| deployment.frontend_url.clone())
        .collect();
    Ok(deployments)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_frontend_of_a_listed_deployment_reaches_the_browser() {
        let known: HashSet<String> = ["https://sak.alpha.pelle.io".to_string()].into();
        assert_eq!(
            checked_url(&known, "https://sak.alpha.pelle.io")
                .unwrap()
                .as_str(),
            "https://sak.alpha.pelle.io/"
        );
        for url in [
            // any other https site, also of the same root domain
            "https://evil.example",
            "https://sak.beta.pelle.io",
            // the known one with something added, or another scheme
            "https://sak.alpha.pelle.io/x",
            "http://sak.alpha.pelle.io",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "",
        ] {
            assert!(checked_url(&known, url).is_err(), "{url}");
        }
        // a control character is refused even in a url that is known
        let with_newline: HashSet<String> = ["https://sak.alpha.pelle.io\n".to_string()].into();
        assert!(checked_url(&with_newline, "https://sak.alpha.pelle.io\n").is_err());
        // a known url that is no https url with a host is refused too
        let http: HashSet<String> = ["http://sak.alpha.pelle.io".to_string()].into();
        assert!(checked_url(&http, "http://sak.alpha.pelle.io").is_err());
    }
}
