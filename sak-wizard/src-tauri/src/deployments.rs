//! the sak deployments found at the providers: everything labeled with a sak name, grouped by
//! it and diffed against the resource group a deployment consists of; and a new one, planned
//! and created from its name and password

use crate::providers::{ProviderTokens, ProviderType, ProviderTypeResponse};
use rust_i18n::t;
use sak_cloud::api::{Applied, ApplyEvent, DiffStatus};
use sak_cloud::hetzner::api::{HetznerApi, Location, ServerType};
use sak_cloud::hetzner::deployment::{
    DeploymentSpec, DeploymentState, DeploymentStatus, ResourceState, check_name, discover, state,
};
use sak_cloud::providers::hetzner::HetznerProviderRuntime;
use serde::Serialize;
use std::sync::Arc;
use tauri::State;
use tauri::ipc::Channel;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeploymentResponse {
    provider: ProviderTypeResponse,
    name: String,
    status: DeploymentStatus,
    resources: Vec<ResourceState>,
}

impl DeploymentResponse {
    fn of(provider: ProviderType, state: DeploymentState) -> Self {
        DeploymentResponse {
            provider: provider.response(),
            name: state.name,
            status: state.status,
            resources: state.resources,
        }
    }
}

/// the plan of a new deployment: what creating it would do, resource by resource
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanResponse {
    name: String,
    public_key: String,
    resources: Vec<PlannedResource>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlannedResource {
    /// `server 'alpha'`
    resource: String,
    status: DiffStatus,
    recreate: bool,
    changes: Vec<String>,
}

/// what creating a deployment reports as it goes: a resource taken up, a resource done; the
/// command itself answers when everything is done or failed
#[derive(Clone, Serialize)]
#[serde(tag = "event", rename_all = "camelCase")]
pub enum CreateEvent {
    Started { resource: String },
    Applied { resource: String, applied: Applied },
}

/// what the environment proposes for a new deployment: SAK_NAME, SAK_PASSWORD and
/// SAK_LOCATION, so a scripted run types nothing; no Debug, the password never shows up
/// in a log
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeploymentDefaults {
    name: Option<String>,
    password: Option<String>,
    location: Option<String>,
}

/// a location to create a deployment in, as the page offers it
#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocationResponse {
    id: Location,
    name: &'static str,
}

/// hetzner's locations; the first is the one proposed
const LOCATIONS: &[LocationResponse] = &[
    LocationResponse {
        id: Location::hel1,
        name: "Helsinki, Finland",
    },
    LocationResponse {
        id: Location::nbg1,
        name: "Nuremberg, Germany",
    },
    LocationResponse {
        id: Location::fsn1,
        name: "Falkenstein, Germany",
    },
    LocationResponse {
        id: Location::ash,
        name: "Ashburn, USA",
    },
    LocationResponse {
        id: Location::hil,
        name: "Hillsboro, USA",
    },
    LocationResponse {
        id: Location::sin,
        name: "Singapore",
    },
];

/// the server type of a deployment: cx23 where hetzner offers it, the european
/// locations, cpx21 elsewhere
fn server_type_for(location: Location) -> ServerType {
    match location {
        Location::fsn1 | Location::nbg1 | Location::hel1 => ServerType::cx23,
        Location::ash | Location::hil | Location::sin => ServerType::cpx21,
    }
}

fn variable(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

/// the password of a deployment, the ssh key is derived from it
const MIN_PASSWORD_CHARS: usize = 16;

/// a new deployment checked and ready: where it goes and what it consists of
struct Prepared {
    provider: ProviderType,
    runtime: HetznerProviderRuntime,
    spec: DeploymentSpec,
}

fn failed(provider: ProviderType, error: impl ToString) -> String {
    message("deployments.failed", provider, error)
}

/// a message about the provider: `key` names what failed, listing, planning or creating
fn message(key: &str, provider: ProviderType, error: impl ToString) -> String {
    t!(key, provider = provider.name(), error = error.to_string()).to_string()
}

/// manages the sak deployments at the providers: finds them by the name label and diffs
/// them against the resource group a deployment consists of, plans and creates new ones.
/// The tokens come from the provider tokens of the app at every call, a token added later
/// counts right away
#[derive(Default)]
pub struct DeploymentManager;

impl DeploymentManager {
    /// the deployments at every provider with a token, by provider and name
    pub async fn list(
        &self,
        tokens: Vec<(ProviderType, String)>,
    ) -> Result<Vec<DeploymentResponse>, String> {
        let mut deployments = Vec::new();
        for (provider, token) in tokens {
            match provider {
                ProviderType::Hetzner => {
                    let api = Arc::new(HetznerApi::new(&token));
                    let runtime = HetznerProviderRuntime::from_api(
                        api.clone(),
                        Location::nbg1,
                        ServerType::cx23,
                    );
                    let discovered = discover(&api)
                        .await
                        .map_err(|error| failed(provider, error))?;
                    for deployment in &discovered {
                        let state = state(&runtime, deployment)
                            .await
                            .map_err(|error| failed(provider, error))?;
                        deployments.push(DeploymentResponse::of(provider, state));
                    }
                }
            }
        }
        Ok(deployments)
    }

    /// checks the name (a sak name no deployment of the project carries yet) and the
    /// password (long enough, typed twice the same), derives the ssh key and the password
    /// hash and lays out the resource group at the first hetzner project
    async fn prepare(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
        password: &str,
        password_repeat: &str,
        location: Location,
    ) -> Result<Prepared, String> {
        let name = name.trim();
        check_name(name).map_err(|_| t!("deployments.invalid_name").to_string())?;
        if password.chars().count() < MIN_PASSWORD_CHARS {
            return Err(t!("deployments.short_password", min = MIN_PASSWORD_CHARS).to_string());
        }
        if password != password_repeat {
            return Err(t!("deployments.password_mismatch").to_string());
        }
        let Some((provider, token)) = tokens
            .into_iter()
            .find(|(provider, _)| *provider == ProviderType::Hetzner)
        else {
            return Err(t!("deployments.no_provider").to_string());
        };
        let api = Arc::new(HetznerApi::new(&token));
        let existing = discover(&api)
            .await
            .map_err(|error| failed(provider, error))?;
        if existing.iter().any(|deployment| deployment.name == name) {
            return Err(t!("deployments.name_taken", name = name).to_string());
        }

        let public_key = crate::keys::derive_public_key(name, password)?;
        let password_hash = crate::keys::password_hash(name, password)?;
        let runtime =
            HetznerProviderRuntime::from_api(api.clone(), location, server_type_for(location));
        let spec = DeploymentSpec::new(
            name,
            &public_key,
            runtime.default_location,
            runtime.default_server_type,
        )
        .map_err(|error| error.to_string())?
        .with_password_hash(&password_hash);
        Ok(Prepared {
            provider,
            runtime,
            spec,
        })
    }

    /// what creating the deployment would do: its resources diffed against the project
    pub async fn plan(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
        password: &str,
        password_repeat: &str,
        location: Location,
    ) -> Result<PlanResponse, String> {
        let prepared = self
            .prepare(tokens, name, password, password_repeat, location)
            .await?;
        let mut registry = prepared
            .spec
            .registry(&prepared.runtime)
            .map_err(|error| error.to_string())?;
        let diffs = registry
            .diff_all()
            .await
            .map_err(|error| message("deployments.plan_failed", prepared.provider, error))?;
        Ok(PlanResponse {
            name: prepared.spec.name.clone(),
            public_key: prepared.spec.public_key.clone(),
            resources: diffs
                .iter()
                .map(|diff| PlannedResource {
                    resource: diff.resource.log_text.clone(),
                    status: diff.status,
                    recreate: diff.needs_recreate(),
                    changes: diff
                        .changes
                        .iter()
                        .map(|change| change.log_text())
                        .collect(),
                })
                .collect(),
        })
    }

    /// creates the deployment: the resources applied in the order of their dependencies,
    /// each reported to `on_event` as it is taken up and done. What exists already is left
    /// as it is, so a run that failed half way is finished by the next
    pub async fn create(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
        password: &str,
        password_repeat: &str,
        location: Location,
        on_event: Channel<CreateEvent>,
    ) -> Result<(), String> {
        let prepared = self
            .prepare(tokens, name, password, password_repeat, location)
            .await?;
        let mut registry = prepared
            .spec
            .registry(&prepared.runtime)
            .map_err(|error| error.to_string())?;
        // a progress event that cannot be delivered is no reason to stop creating
        let mut report = |event: ApplyEvent| {
            let event = match event {
                ApplyEvent::Started(resource) => CreateEvent::Started {
                    resource: resource.log_text,
                },
                ApplyEvent::Applied(result) => CreateEvent::Applied {
                    resource: result.resource.log_text,
                    applied: result.applied,
                },
            };
            let _ = on_event.send(event);
        };
        registry
            .apply_all_with(false, &mut report)
            .await
            .map_err(|error| message("deployments.create_failed", prepared.provider, error))?;
        Ok(())
    }
}

#[tauri::command]
pub fn deployment_defaults() -> DeploymentDefaults {
    DeploymentDefaults {
        name: variable("SAK_NAME"),
        password: variable("SAK_PASSWORD"),
        location: variable("SAK_LOCATION"),
    }
}

#[tauri::command]
pub fn locations() -> Vec<LocationResponse> {
    LOCATIONS.to_vec()
}

#[tauri::command]
pub async fn plan_deployment(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    name: String,
    password: String,
    password_repeat: String,
    location: Location,
) -> Result<PlanResponse, String> {
    let tokens = tokens.tokens();
    manager
        .plan(tokens, &name, &password, &password_repeat, location)
        .await
}

#[tauri::command]
pub async fn create_deployment(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    name: String,
    password: String,
    password_repeat: String,
    location: Location,
    on_event: Channel<CreateEvent>,
) -> Result<(), String> {
    let tokens = tokens.tokens();
    manager
        .create(
            tokens,
            &name,
            &password,
            &password_repeat,
            location,
            on_event,
        )
        .await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_server_type_follows_the_location() {
        assert_eq!(server_type_for(Location::hel1), ServerType::cx23);
        assert_eq!(server_type_for(Location::sin), ServerType::cpx21);
        assert_eq!(LOCATIONS[0].id, Location::hel1);
    }
}

#[tauri::command]
pub async fn list_deployments(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
) -> Result<Vec<DeploymentResponse>, String> {
    let tokens = tokens.tokens();
    manager.list(tokens).await
}
