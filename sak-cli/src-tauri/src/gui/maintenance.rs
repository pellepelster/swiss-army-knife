//! the command of the maintenance view: the status, the update or the reboot of the server of a
//! deployment, as `sakctl maintenance` has them (`maintenance::perform`). Each line of the server
//! goes to the view while it runs, the answer comes as lines. It cannot be cancelled, each command
//! on the server has its timeout; while it runs, sakctl is not restarted

use crate::core::container::ContainerRuntime;
use crate::core::deployments::DeploymentManager;
use crate::core::error::WizardError;
use crate::core::maintenance::{self, Line};
use crate::core::providers::ProviderTokens;
use crate::gui::deployments::RunningApply;
use rust_i18n::t;
use serde::Deserialize;
use tauri::State;
use tauri::ipc::Channel;

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Action {
    Status,
    Update,
    /// a reboot whether one is pending or not; it waits until the deployment answers again
    Reboot,
}

/// what the view asks for, on the server of which deployment; no `Debug`, it holds the password
#[derive(Deserialize)]
pub struct MaintenanceRequest {
    action: Action,
    name: String,
    password: String,
}

#[tauri::command]
pub async fn run_maintenance(
    manager: State<'_, DeploymentManager>,
    tokens: State<'_, ProviderTokens>,
    container: State<'_, ContainerRuntime>,
    running: State<'_, RunningApply>,
    request: MaintenanceRequest,
    on_line: Channel<String>,
) -> Result<Vec<Line>, WizardError> {
    let name = request.name.trim();
    let action = match request.action {
        Action::Status => maintenance::Action::Status,
        Action::Update => maintenance::Action::Update,
        Action::Reboot => maintenance::Action::Reboot {
            if_pending: false,
            wait: true,
        },
    };
    // a line that cannot be sent is no reason to stop the maintenance
    let send = move |line: String| {
        let _ = on_line.send(line);
    };
    let work = maintenance::perform(
        &manager,
        tokens.tokens(),
        &container,
        name,
        &request.password,
        action,
        send,
    );
    // the view has no cancel; this keeps a restart from stopping it halfway
    let answer = running.run(work, "shared.deployments.cancelled").await?;
    Ok(answer.lines(name, &t!("ui.maintenance.reboot_hint")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_view_asks_for_an_action_on_a_deployment() {
        let request: MaintenanceRequest =
            serde_json::from_str(r#"{"action": "reboot", "name": "alpha", "password": "secret"}"#)
                .unwrap();
        assert_eq!(request.action, Action::Reboot);
        for action in ["status", "update"] {
            let json = format!(r#"{{"action": "{action}", "name": "a", "password": "p"}}"#);
            assert!(
                serde_json::from_str::<MaintenanceRequest>(&json).is_ok(),
                "{action}"
            );
        }
        let unknown = r#"{"action": "shutdown", "name": "a", "password": "p"}"#;
        assert!(serde_json::from_str::<MaintenanceRequest>(unknown).is_err());
    }
}
