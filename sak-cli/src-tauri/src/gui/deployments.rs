//! the commands of the pages about deployments, each a thin call into core; what the window keeps
//! between them is in `state`

use crate::core::container::ContainerRuntime;
use crate::core::deployments::{
    DeploymentDefaults, DeploymentEvent, DeploymentManager, DeploymentRequest, DeploymentResponse,
    LocationResponse, PlanResponse, RootDomainResponse, defaults,
};
use crate::core::error::SakctlError;
use crate::core::providers::{ProviderTokens, ProviderType};
use crate::gui::state::{CheckedDeployment, RunningWork};
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
    deployment: DeploymentRequest,
) -> Result<PlanResponse, SakctlError> {
    let tokens = tokens.tokens();
    manager.plan(tokens, &deployment, &container).await
}

/// the plan of a check and its number, which the deploy names
#[derive(serde::Serialize)]
pub struct CheckResponse {
    check: u64,
    #[serde(flatten)]
    plan: PlanResponse,
}

/// the check of the page of a deployment (`DeploymentManager::check`); the deploy it planned is
/// kept for `apply_checked`, so a deploy applies what was checked
#[tauri::command]
pub async fn check_deployment(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    container: State<'_, ContainerRuntime>,
    checked: State<'_, CheckedDeployment>,
    name: String,
    password: String,
    location: Option<sak_cloud::hetzner::api::Location>,
) -> Result<CheckResponse, SakctlError> {
    let number = checked.start();
    let location = location.unwrap_or_else(default_location);
    let (deployment, plan) = manager
        .check(tokens.tokens(), &name, &password, location, &container)
        .await?;
    checked.done(number, deployment, plan.seen());
    Ok(CheckResponse {
        check: number,
        plan,
    })
}

/// the location of a deployment that has no located resource left (no server, volume or
/// address): SAK_LOCATION, else nbg1, as on the command line. It counts only for what a deploy
/// creates anew
fn default_location() -> sak_cloud::hetzner::api::Location {
    std::env::var(crate::core::deployments::LOCATION_VARIABLE)
        .ok()
        .and_then(|value| {
            crate::core::deployments::LOCATIONS
                .iter()
                .find(|location| location.id.to_string() == value.trim())
                .map(|location| location.id)
        })
        .unwrap_or(sak_cloud::hetzner::api::Location::nbg1)
}

/// which check a deploy applies, forced or not
#[derive(serde::Deserialize)]
pub struct CheckedDeploy {
    check: u64,
    force: bool,
}

/// the deploy of a check; refused when it is not the newest check
#[tauri::command]
pub async fn apply_checked(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    container: State<'_, ContainerRuntime>,
    running: State<'_, RunningWork>,
    checked: State<'_, CheckedDeployment>,
    deploy: CheckedDeploy,
    on_event: Channel<DeploymentEvent>,
) -> Result<(), SakctlError> {
    let CheckedDeploy { check, force } = deploy;
    let (deployment, seen) = checked
        .take(check)
        .ok_or_else(|| t!("ui.deployment.not_checked").to_string())?;
    let deployment = DeploymentRequest {
        force,
        ..deployment
    };
    // a progress event that cannot be sent is no reason to stop the apply
    let mut report = |event| {
        let _ = on_event.send(event);
    };
    // the apply plans again and applies only what the page showed: a project that changed since
    // the check is checked again first. The apply applies this plan
    let work = async {
        let prepared = manager.prepare(tokens.tokens(), &deployment).await?;
        let planned = manager.plan_prepared(prepared, &container).await?;
        if planned.plan.seen() != seen {
            return Err(SakctlError::from(
                t!("ui.deployment.changed_since_check").to_string(),
            ));
        }
        manager.apply(planned, &container, &mut report).await
    };
    running.run(work, "shared.deployments.cancelled").await
}

#[tauri::command]
pub async fn apply_deployment(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    container: State<'_, ContainerRuntime>,
    running: State<'_, RunningWork>,
    deployment: DeploymentRequest,
    on_event: Channel<DeploymentEvent>,
) -> Result<(), SakctlError> {
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
    running: &RunningWork,
    deployment: &DeploymentRequest,
    on_event: &Channel<DeploymentEvent>,
) -> Result<(), SakctlError> {
    // a progress event that cannot be sent is no reason to stop the apply
    let mut report = |event| {
        let _ = on_event.send(event);
    };
    running
        .run(
            manager.prepare_and_apply(tokens, deployment, container, &mut report),
            "shared.deployments.cancelled",
        )
        .await
}

/// stops the apply or the maintenance that runs: its requests to the provider end where they are,
/// the container of a provisioning or an ssh session is removed. What was done stays, the plan of
/// the next deploy shows the rest
#[tauri::command]
pub fn cancel_apply(running: State<'_, RunningWork>) {
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
        .map_err(|error| error.message)
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
    fn a_deployment_without_a_location_is_checked_at_the_one_of_the_environment_else_nbg1() {
        use crate::core::deployments::LOCATION_VARIABLE;
        use sak_cloud::hetzner::api::Location;
        // SAFETY: nextest runs each test in a process of its own, nothing reads the variable
        // at the same time
        unsafe { std::env::remove_var(LOCATION_VARIABLE) };
        assert_eq!(default_location(), Location::nbg1);
        unsafe { std::env::set_var(LOCATION_VARIABLE, " hel1 ") };
        assert_eq!(default_location(), Location::hel1);
        // a location sakctl does not offer counts as none
        unsafe { std::env::set_var(LOCATION_VARIABLE, "unknown") };
        assert_eq!(default_location(), Location::nbg1);
    }

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
