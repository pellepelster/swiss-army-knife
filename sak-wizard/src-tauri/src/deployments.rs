//! the sak deployments found at the providers: everything labeled with a sak name, grouped by
//! it and diffed against the resource group a deployment consists of

use crate::providers::{ProviderTokens, ProviderType, ProviderTypeResponse};
use rust_i18n::t;
use sak_cloud::api::DiffStatus;
use sak_cloud::hetzner::api::{HetznerApi, Location, ServerType};
use sak_cloud::hetzner::deployment::{
    DeploymentSpec, DeploymentState, DeploymentStatus, ResourceState, check_name, discover, state,
};
use sak_cloud::providers::hetzner::HetznerProviderRuntime;
use serde::Serialize;
use std::sync::Arc;
use tauri::State;

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

/// manages the sak deployments at the providers: finds them by the name label and diffs
/// them against the resource group a deployment consists of. The tokens come from the
/// provider tokens of the app at every call, a token added later counts right away
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
            let failed = |error: String| {
                t!(
                    "deployments.failed",
                    provider = provider.name(),
                    error = error
                )
                .to_string()
            };
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
                        .map_err(|error| failed(error.to_string()))?;
                    for deployment in &discovered {
                        let state = state(&runtime, deployment)
                            .await
                            .map_err(|error| failed(error.to_string()))?;
                        deployments.push(DeploymentResponse::of(provider, state));
                    }
                }
            }
        }
        Ok(deployments)
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

/// the password of a deployment, the ssh key is derived from it
const MIN_PASSWORD_CHARS: usize = 16;

impl DeploymentManager {
    /// checks the name and the password, derives the key and diffs the resource group of
    /// the deployment against what the first hetzner project holds
    pub async fn plan(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
        password: &str,
        password_repeat: &str,
    ) -> Result<PlanResponse, String> {
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
        let failed = |error: String| {
            t!(
                "deployments.failed",
                provider = provider.name(),
                error = error
            )
            .to_string()
        };
        let api = Arc::new(HetznerApi::new(&token));
        let existing = discover(&api)
            .await
            .map_err(|error| failed(error.to_string()))?;
        if existing.iter().any(|deployment| deployment.name == name) {
            return Err(t!("deployments.name_taken", name = name).to_string());
        }

        let public_key = crate::keys::derive_public_key(name, password)?;
        let runtime =
            HetznerProviderRuntime::from_api(api.clone(), Location::hel1, ServerType::cx23);
        let spec = DeploymentSpec::new(
            name,
            &public_key,
            runtime.default_location,
            runtime.default_server_type,
        )
        .map_err(|error| error.to_string())?;
        let mut registry = spec.registry(&runtime).map_err(|error| error.to_string())?;
        let diffs = registry
            .diff_all()
            .await
            .map_err(|error| failed(error.to_string()))?;
        Ok(PlanResponse {
            name: name.to_string(),
            public_key,
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
}

#[tauri::command]
pub async fn plan_deployment(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    name: String,
    password: String,
    password_repeat: String,
) -> Result<PlanResponse, String> {
    let tokens = tokens.tokens();
    manager
        .plan(tokens, &name, &password, &password_repeat)
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
