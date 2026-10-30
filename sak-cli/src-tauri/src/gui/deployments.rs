//! the commands of the pages about deployments, each a thin call into core

use crate::core::container::ContainerRuntime;
use crate::core::deployments::{
    CreateEvent, DeploymentDefaults, DeploymentManager, DeploymentResponse, Intent,
    LocationResponse, NewDeployment, PlanResponse, RootDomainResponse, complete, defaults,
};
use crate::core::error::WizardError;
use crate::core::providers::{ProviderTokens, ProviderType};
use futures_util::future::{AbortHandle, Abortable};
use rust_i18n::t;
use std::collections::HashSet;
use std::sync::Mutex;
use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;
use url::Url;

/// the frontend urls of the deployments of the last list: the only urls `open_url` opens
#[derive(Default)]
pub struct FrontendUrls(Mutex<HashSet<String>>);

#[tauri::command]
pub fn deployment_defaults() -> DeploymentDefaults {
    defaults()
}

/// opens the frontend of a deployment in the browser of the system: only a url of the last list
/// of deployments. The page has no opener of its own
#[tauri::command]
pub fn open_url(app: AppHandle, known: State<'_, FrontendUrls>, url: String) -> Result<(), String> {
    let known = known.0.lock().map_err(|e| e.to_string())?;
    let url = checked_url(&known, &url)?;
    app.opener()
        .open_url(url.as_str(), None::<&str>)
        .map_err(|error| error.to_string())
}

/// the url when it is one of `known` and an https url with a host and no control character
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

/// checks a name when the user leaves its field; the message when the name is not valid
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

/// the plan of a create or a destroy, as the form asks for it
#[tauri::command]
pub async fn plan_deployment(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    container: State<'_, ContainerRuntime>,
    deployment: NewDeployment,
) -> Result<PlanResponse, WizardError> {
    let tokens = tokens.tokens();
    manager.plan(tokens, &deployment, &container).await
}

/// the apply that runs, so cancel can stop it
#[derive(Default)]
pub struct RunningApply(Mutex<Option<AbortHandle>>);

impl RunningApply {
    pub fn is_running(&self) -> bool {
        self.0.lock().is_ok_and(|running| running.is_some())
    }
}

/// the deploy the last check of the page of a deployment planned, as `apply_checked` applies it
#[derive(Default)]
pub struct CheckedDeployment(Mutex<Option<NewDeployment>>);

/// the check of the page of a deployment: a deploy of the deployment as it is, its settings
/// detected and completed as the command line does (`deployments::complete`), and its plan. The
/// deploy is kept for `apply_checked`, so a deploy applies what was checked
#[tauri::command]
pub async fn check_deployment(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    container: State<'_, ContainerRuntime>,
    checked: State<'_, CheckedDeployment>,
    name: String,
    password: String,
    location: sak_cloud::hetzner::api::Location,
) -> Result<PlanResponse, WizardError> {
    let tokens = tokens.tokens();
    if let Ok(mut checked) = checked.0.lock() {
        *checked = None;
    }
    let deployment = NewDeployment {
        name: name.clone(),
        password: password.clone(),
        password_repeat: password.clone(),
        location,
        dns_mode: None,
        root_domain: None,
        intent: Intent::Deploy,
        delete_data: false,
        user_mode: None,
        acme_ca: None,
        smtp: None,
        admin_email: None,
        force: false,
    };
    let detected = manager.detected(tokens.clone(), &name, &password).await?;
    let deployment = complete(deployment, &detected, "")?;
    let plan = manager.plan(tokens, &deployment, &container).await?;
    if let Ok(mut checked) = checked.0.lock() {
        *checked = Some(deployment);
    }
    Ok(plan)
}

/// the deploy of the last check, forced or not
#[tauri::command]
pub async fn apply_checked(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    container: State<'_, ContainerRuntime>,
    running: State<'_, RunningApply>,
    checked: State<'_, CheckedDeployment>,
    force: bool,
    on_event: Channel<CreateEvent>,
) -> Result<(), WizardError> {
    let deployment = checked
        .0
        .lock()
        .ok()
        .and_then(|mut checked| checked.take())
        .ok_or_else(|| t!("ui.deployment.not_checked").to_string())?;
    let deployment = NewDeployment {
        force,
        ..deployment
    };
    apply(
        &manager,
        tokens.tokens(),
        &container,
        &running,
        &deployment,
        &on_event,
    )
    .await
}

#[tauri::command]
pub async fn create_deployment(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    container: State<'_, ContainerRuntime>,
    running: State<'_, RunningApply>,
    deployment: NewDeployment,
    on_event: Channel<CreateEvent>,
) -> Result<(), WizardError> {
    apply(
        &manager,
        tokens.tokens(),
        &container,
        &running,
        &deployment,
        &on_event,
    )
    .await
}

/// the apply of `deployment`, which cancel can stop; each event goes to the page
async fn apply(
    manager: &DeploymentManager,
    tokens: Vec<(ProviderType, String)>,
    container: &ContainerRuntime,
    running: &RunningApply,
    deployment: &NewDeployment,
    on_event: &Channel<CreateEvent>,
) -> Result<(), WizardError> {
    let (abort, registration) = AbortHandle::new_pair();
    if let Ok(mut running) = running.0.lock() {
        *running = Some(abort);
    }
    // a progress event that cannot be sent is no reason to stop the apply
    let mut report = |event| {
        let _ = on_event.send(event);
    };
    let apply = manager.create(tokens, deployment, container, &mut report);
    let result = Abortable::new(apply, registration).await;
    if let Ok(mut running) = running.0.lock() {
        *running = None;
    }
    result.unwrap_or_else(|_| Err(t!("shared.deployments.cancelled").to_string().into()))
}

/// stops the apply that runs: its requests to the provider end where they are, the container of
/// a provisioning is removed. What was done stays, the plan of the next deploy shows the rest
#[tauri::command]
pub fn cancel_apply(running: State<'_, RunningApply>) {
    if let Some(abort) = running.0.lock().ok().and_then(|mut running| running.take()) {
        abort.abort();
    }
    crate::core::provision::stop_runs();
}

/// whether the password is the one of the deployment, asked while it is typed
#[tauri::command]
pub async fn check_deployment_password(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    name: String,
    password: String,
) -> Result<(), String> {
    manager
        .check_password(tokens.tokens(), &name, &password)
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
    // the page can open the frontends of these deployments and no other url
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
            // another https site, also of the same root domain
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
