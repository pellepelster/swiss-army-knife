//! the commands of the pages about deployments, each a thin call into core

use crate::core::deployments::{
    CreateEvent, DeploymentDefaults, DeploymentManager, DeploymentResponse, LocationResponse,
    NewDeployment, PlanResponse, defaults,
};
use crate::core::providers::ProviderTokens;
use tauri::State;
use tauri::ipc::Channel;

#[tauri::command]
pub fn deployment_defaults() -> DeploymentDefaults {
    defaults()
}

#[tauri::command]
pub fn locations() -> Vec<LocationResponse> {
    crate::core::deployments::locations()
}

#[tauri::command]
pub async fn plan_deployment(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    deployment: NewDeployment,
) -> Result<PlanResponse, String> {
    let tokens = tokens.tokens();
    manager.plan(tokens, &deployment).await
}

#[tauri::command]
pub async fn create_deployment(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    deployment: NewDeployment,
    on_event: Channel<CreateEvent>,
) -> Result<(), String> {
    let tokens = tokens.tokens();
    // a progress event that cannot be delivered is no reason to stop creating
    manager
        .create(tokens, &deployment, &mut |event| {
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
