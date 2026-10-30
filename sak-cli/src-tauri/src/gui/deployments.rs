//! the commands of the pages about deployments, each a thin call into core

use crate::core::container::ContainerRuntime;
use crate::core::deployments::{
    CreateEvent, DeploymentDefaults, DeploymentManager, DeploymentResponse, Intent,
    LocationResponse, NewDeployment, PlanResponse, RootDomainResponse, defaults,
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

/// the apply or the maintenance that runs, so cancel can stop it
#[derive(Default)]
pub struct RunningApply(Mutex<Option<AbortHandle>>);

impl RunningApply {
    pub fn is_running(&self) -> bool {
        self.0.lock().is_ok_and(|running| running.is_some())
    }

    /// `work` until it ends, or until cancel stops it: then the error is the text `cancelled`
    pub async fn run<T, E: From<String>>(
        &self,
        work: impl std::future::Future<Output = Result<T, E>>,
        cancelled: &str,
    ) -> Result<T, E> {
        let (abort, registration) = AbortHandle::new_pair();
        if let Ok(mut running) = self.0.lock() {
            *running = Some(abort);
        }
        let result = Abortable::new(work, registration).await;
        if let Ok(mut running) = self.0.lock() {
            *running = None;
        }
        result.unwrap_or_else(|_| Err(t!(cancelled).to_string().into()))
    }
}

/// the deploy the last check of the page of a deployment planned, as `apply_checked` applies it.
/// Each check has its number; only the newest one is kept, and a deploy names the check it applies.
/// So a check that ends after a newer one (of another deployment, after the user went back and
/// opened it) is never what a deploy applies
#[derive(Default)]
pub struct CheckedDeployment(Mutex<Checks>);

#[derive(Default)]
struct Checks {
    /// the number of the newest check that started
    newest: u64,
    /// the newest check that ended, with its number
    done: Option<(u64, NewDeployment)>,
}

impl CheckedDeployment {
    /// the number of a check that starts; what an older check kept goes
    fn start(&self) -> u64 {
        let mut checks = self
            .0
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        checks.newest += 1;
        checks.done = None;
        checks.newest
    }

    /// keeps the deploy of the check `number`, unless a newer check started meanwhile
    fn done(&self, number: u64, deployment: NewDeployment) {
        let mut checks = self
            .0
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if checks.newest == number {
            checks.done = Some((number, deployment));
        }
    }

    /// the deploy of the check `number`, once; none when it is not the newest check that ended
    fn take(&self, number: u64) -> Option<NewDeployment> {
        let mut checks = self
            .0
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        match checks.done.take() {
            Some((done, deployment)) if done == number => Some(deployment),
            other => {
                checks.done = other;
                None
            }
        }
    }
}

/// the plan of a check and its number, which the deploy names
#[derive(serde::Serialize)]
pub struct CheckResponse {
    check: u64,
    #[serde(flatten)]
    plan: PlanResponse,
}

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
) -> Result<CheckResponse, WizardError> {
    let tokens = tokens.tokens();
    let number = checked.start();
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
    let deployment = manager.completed(tokens.clone(), deployment, "").await?;
    let plan = manager.plan(tokens, &deployment, &container).await?;
    checked.done(number, deployment);
    Ok(CheckResponse {
        check: number,
        plan,
    })
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
    running: State<'_, RunningApply>,
    checked: State<'_, CheckedDeployment>,
    deploy: CheckedDeploy,
    on_event: Channel<CreateEvent>,
) -> Result<(), WizardError> {
    let CheckedDeploy { check, force } = deploy;
    let deployment = checked
        .take(check)
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
    // a progress event that cannot be sent is no reason to stop the apply
    let mut report = |event| {
        let _ = on_event.send(event);
    };
    running
        .run(
            manager.create(tokens, deployment, container, &mut report),
            "shared.deployments.cancelled",
        )
        .await
}

/// stops the apply or the maintenance that runs: its requests to the provider end where they are,
/// the container of a provisioning or an ssh session is removed. What was done stays, the plan of
/// the next deploy shows the rest
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

    fn deployment(name: &str) -> NewDeployment {
        NewDeployment {
            name: name.to_string(),
            password: String::new(),
            password_repeat: String::new(),
            location: sak_cloud::hetzner::api::Location::nbg1,
            dns_mode: None,
            root_domain: None,
            intent: Intent::Deploy,
            delete_data: false,
            user_mode: None,
            acme_ca: None,
            smtp: None,
            admin_email: None,
            force: false,
        }
    }

    #[test]
    fn a_deploy_applies_only_the_newest_check_it_names() {
        let checked = CheckedDeployment::default();
        // the page of alpha checks, the user goes back and opens beta, which checks too
        let alpha = checked.start();
        let beta = checked.start();
        // beta ends first, alpha after it: alpha is not kept
        checked.done(beta, deployment("beta"));
        checked.done(alpha, deployment("alpha"));
        assert!(checked.take(alpha).is_none());
        assert_eq!(checked.take(beta).unwrap().name, "beta");
        // once
        assert!(checked.take(beta).is_none());

        // a deploy that names an older check leaves the newest one as it is
        let gamma = checked.start();
        checked.done(gamma, deployment("gamma"));
        assert!(checked.take(beta).is_none());
        assert_eq!(checked.take(gamma).unwrap().name, "gamma");

        // a check that starts drops what the one before kept
        let delta = checked.start();
        checked.done(delta, deployment("delta"));
        checked.start();
        assert!(checked.take(delta).is_none());
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
