//! the commands of the pages about deployments, each a thin call into core

use crate::core::container::ContainerRuntime;
use crate::core::deployments::{
    CreateEvent, DeploymentDefaults, DeploymentManager, DeploymentResponse, LocationResponse,
    NewDeployment, PlanResponse, RootDomainResponse, defaults,
};
use crate::core::error::WizardError;
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
