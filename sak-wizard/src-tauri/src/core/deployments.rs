//! the sak deployments found at the providers: everything labeled with a sak name, grouped by
//! it and diffed against the resource group a deployment consists of; and a new one, planned
//! and created from its name and password

use crate::core::providers::{ProviderType, ProviderTypeResponse};
use rust_i18n::t;
use sak_cloud::api::{Applied, ApplyEvent, DiffStatus};
use sak_cloud::hetzner::PASSWORD_HASH_LABEL;
use sak_cloud::hetzner::api::{HetznerApi, Location, ServerType};
use sak_cloud::hetzner::deployment::{
    DeploymentSpec, DeploymentState, DeploymentStatus, DiscoveredDeployment, ResourceState,
    check_name, discover, state,
};
use sak_cloud::hetzner::labels::HetznerLabels;
use sak_cloud::providers::hetzner::HetznerProviderRuntime;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeploymentResponse {
    pub(crate) provider: ProviderTypeResponse,
    pub(crate) name: String,
    pub(crate) status: DeploymentStatus,
    pub(crate) location: Option<Location>,
    pub(crate) resources: Vec<ResourceState>,
}

impl DeploymentResponse {
    fn of(provider: ProviderType, state: DeploymentState) -> Self {
        DeploymentResponse {
            provider: provider.response(),
            name: state.name,
            status: state.status,
            location: state.location,
            resources: state.resources,
        }
    }
}

/// the plan of a new deployment: what creating it would do, resource by resource
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanResponse {
    pub(crate) name: String,
    pub(crate) public_key: String,
    pub(crate) resources: Vec<PlannedResource>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlannedResource {
    /// `server 'alpha'`
    pub(crate) resource: String,
    pub(crate) status: DiffStatus,
    pub(crate) recreate: bool,
    pub(crate) changes: Vec<String>,
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

/// the variables a new deployment takes its name, password and location from, the pages
/// and the command line alike
pub const NAME_VARIABLE: &str = "SAK_NAME";
pub const PASSWORD_VARIABLE: &str = "SAK_PASSWORD";
pub const LOCATION_VARIABLE: &str = "SAK_LOCATION";

/// a location to create a deployment in, as the page and the command line offer it
#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocationResponse {
    pub(crate) id: Location,
    pub(crate) name: &'static str,
}

/// hetzner's locations; the first is the one proposed
pub(crate) const LOCATIONS: &[LocationResponse] = &[
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

/// the name of a serialized enum value, `up_to_date`, `created`: what the page reads in the
/// json, the command line in its texts
pub(crate) fn key<T: Serialize>(value: &T) -> String {
    serde_json::to_value(value)
        .ok()
        .and_then(|value| value.as_str().map(str::to_string))
        .unwrap_or_default()
}

/// what the environment proposes for a new deployment
pub fn defaults() -> DeploymentDefaults {
    DeploymentDefaults {
        name: variable(NAME_VARIABLE),
        password: variable(PASSWORD_VARIABLE),
        location: variable(LOCATION_VARIABLE),
    }
}

/// the locations a deployment can be created in
pub fn locations() -> Vec<LocationResponse> {
    LOCATIONS.to_vec()
}

fn variable(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

/// the password of a deployment, the ssh key is derived from it
const MIN_PASSWORD_CHARS: usize = 16;

/// what a plan or an apply is for: a deployment that does not exist yet, or one that does
/// and is brought to what the wizard declares, its server created anew when it must be
#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Intent {
    Create,
    Redeploy,
}

/// what the page sends for a deployment to plan or create
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewDeployment {
    pub name: String,
    pub password: String,
    pub password_repeat: String,
    pub location: Location,
    pub intent: Intent,
}

/// a deployment checked and ready: where it goes, what it consists of, whether a resource
/// may be created anew
struct Prepared {
    provider: ProviderType,
    runtime: HetznerProviderRuntime,
    spec: DeploymentSpec,
    recreate: bool,
}

/// the password of an existing deployment, against the hash on its server or, without a
/// server, against the key derived from it
fn verify_password(
    deployment: &DiscoveredDeployment,
    name: &str,
    password: &str,
) -> Result<(), String> {
    let wrong = || t!("deployments.wrong_password", name = name).to_string();
    if let Some(server) = &deployment.server {
        let labels = HetznerLabels::from_raw(server.labels.clone()).labels();
        let hash = crate::core::keys::password_hash(name, password)?;
        if labels.get(PASSWORD_HASH_LABEL) != Some(&hash) {
            return Err(wrong());
        }
    } else if let Some(key) = &deployment.ssh_key {
        let derived = crate::core::keys::derive_public_key(name, password)?;
        // the blob alone, hetzner may keep the key without its comment
        if key.public_key.split_whitespace().nth(1) != derived.split_whitespace().nth(1) {
            return Err(wrong());
        }
    }
    Ok(())
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
        deployment: &NewDeployment,
    ) -> Result<Prepared, String> {
        let NewDeployment {
            name,
            password,
            password_repeat,
            location,
            intent,
        } = deployment;
        let (location, intent) = (*location, *intent);
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
            .map_err(|error| failed(provider, error))?
            .into_iter()
            .find(|deployment| deployment.name == name);
        let (location, recreate) = match intent {
            Intent::Create => {
                if existing.is_some() {
                    return Err(t!("deployments.name_taken", name = name).to_string());
                }
                (location, false)
            }
            Intent::Redeploy => {
                let Some(existing) = existing else {
                    return Err(t!("deployments.not_found", name = name).to_string());
                };
                verify_password(&existing, name, password)?;
                // the server stays where it is
                let location = existing
                    .server
                    .as_ref()
                    .map(|server| server.location)
                    .unwrap_or(location);
                (location, true)
            }
        };

        let public_key = crate::core::keys::derive_public_key(name, password)?;
        let password_hash = crate::core::keys::password_hash(name, password)?;
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
            recreate,
        })
    }

    /// what creating the deployment would do: its resources diffed against the project
    pub async fn plan(
        &self,
        tokens: Vec<(ProviderType, String)>,
        deployment: &NewDeployment,
    ) -> Result<PlanResponse, String> {
        let prepared = self.prepare(tokens, deployment).await?;
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
        deployment: &NewDeployment,
        on_event: &mut (dyn FnMut(CreateEvent) + Send),
    ) -> Result<(), String> {
        let prepared = self.prepare(tokens, deployment).await?;
        let mut registry = prepared
            .spec
            .registry(&prepared.runtime)
            .map_err(|error| error.to_string())?;
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
            on_event(event);
        };
        registry
            .apply_all_with(prepared.recreate, &mut report)
            .await
            .map_err(|error| message("deployments.create_failed", prepared.provider, error))?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_password_of_a_deployment_is_checked_against_its_server_or_its_key() {
        let name = "alpha";
        let password = "a password of sixteen";
        let hash = crate::core::keys::password_hash(name, password).unwrap();
        let key = crate::core::keys::derive_public_key(name, password).unwrap();
        let server: sak_cloud::hetzner::api::servers::Server =
            serde_json::from_value(serde_json::json!({
                "id": 1, "name": name, "status": "running", "server_type": { "id": 1, "name": "cx23" },
                "image": { "id": 2, "name": "debian-13" }, "location": { "id": 3, "name": "hel1" },
                "protection": { "delete": false, "rebuild": false }, "volumes": [],
                "public_net": { "ipv4": null, "ipv6": null }, "private_net": [],
                "labels": { PASSWORD_HASH_LABEL: hash }
            }))
            .unwrap();
        let blob = key.split_whitespace().take(2).collect::<Vec<_>>().join(" ");
        let ssh_key: sak_cloud::hetzner::api::ssh_keys::SshKey =
            serde_json::from_value(serde_json::json!({
                "id": 7, "name": name, "fingerprint": "aa", "public_key": blob, "labels": {}
            }))
            .unwrap();

        let with_server = DiscoveredDeployment {
            name: name.to_string(),
            server: Some(server.into()),
            ..Default::default()
        };
        assert!(verify_password(&with_server, name, password).is_ok());
        assert!(verify_password(&with_server, name, "another password here").is_err());

        let with_key = DiscoveredDeployment {
            name: name.to_string(),
            ssh_key: Some(ssh_key.into()),
            ..Default::default()
        };
        assert!(verify_password(&with_key, name, password).is_ok());
        assert!(verify_password(&with_key, name, "another password here").is_err());
    }

    #[test]
    fn the_server_type_follows_the_location() {
        assert_eq!(server_type_for(Location::hel1), ServerType::cx23);
        assert_eq!(server_type_for(Location::sin), ServerType::cpx21);
        assert_eq!(LOCATIONS[0].id, Location::hel1);
    }
}
