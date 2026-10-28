//! the sak deployments found at the providers: everything labeled with a sak name, grouped by
//! it and diffed against the resource group a deployment consists of

use crate::providers::{ProviderTokens, ProviderType, ProviderTypeResponse};
use rust_i18n::t;
use sak_cloud::hetzner::api::{HetznerApi, Location, ServerType};
use sak_cloud::hetzner::deployment::{
    DeploymentState, DeploymentStatus, ResourceState, discover, state,
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

#[tauri::command]
pub async fn list_deployments(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
) -> Result<Vec<DeploymentResponse>, String> {
    let tokens = tokens.tokens();
    manager.list(tokens).await
}
