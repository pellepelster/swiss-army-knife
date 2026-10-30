//! the command of the maintenance view: the status, the update or the reboot of the server of a
//! deployment, as `sakctl maintenance` has them. Each line of the server goes to the view while it
//! runs, the answer comes as lines. It cannot be cancelled, each command on the server has its
//! timeout; while it runs, sakctl is not restarted

use crate::core::container::ContainerRuntime;
use crate::core::deployments::DeploymentManager;
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

/// what the view asks for, on the server of which deployment
#[derive(Debug, Deserialize)]
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
) -> Result<Vec<Line>, String> {
    let work = run(
        &manager,
        tokens.tokens(),
        &container,
        request.action,
        request.name.trim(),
        &request.password,
        on_line,
    );
    // the view has no cancel; this keeps a restart from stopping it halfway
    running.run(work, "shared.deployments.cancelled").await
}

async fn run(
    manager: &DeploymentManager,
    tokens: Vec<(crate::core::providers::ProviderType, String)>,
    container: &ContainerRuntime,
    action: Action,
    name: &str,
    password: &str,
    on_line: Channel<String>,
) -> Result<Vec<Line>, String> {
    let target = manager.ssh_target(tokens.clone(), name, password).await?;
    let runtime = container
        .get()
        .ok_or_else(|| t!("shared.provision.no_runtime").to_string())?;
    // a line that cannot be sent is no reason to stop the maintenance
    let send = move |line: String| {
        let _ = on_line.send(line);
    };
    send(
        t!(
            "shared.maintenance.connecting",
            name = name,
            host = target.host.as_str()
        )
        .to_string(),
    );
    let mut on_server = send.clone();
    match action {
        Action::Status => {
            let status = maintenance::limited(maintenance::STATUS_TIMEOUT, move || {
                maintenance::status(runtime, &target, &mut on_server)
            })
            .await?;
            Ok(maintenance::status_lines(name, &status))
        }
        Action::Update => {
            let update = maintenance::limited(maintenance::UPDATE_TIMEOUT, move || {
                maintenance::update(runtime, &target, &mut on_server)
            })
            .await?;
            let mut lines = maintenance::update_lines(name, &update);
            if update.reboot_pending {
                lines.push(Line {
                    warn: true,
                    text: t!("ui.maintenance.reboot_hint").to_string(),
                });
            }
            Ok(lines)
        }
        Action::Reboot => {
            let reboot = maintenance::limited(maintenance::REBOOT_TIMEOUT, move || {
                maintenance::reboot(runtime, &target, false, &mut on_server)
            })
            .await?;
            let mut lines = vec![maintenance::reboot_line(name, &reboot)];
            if reboot.rebooting {
                // the wait takes minutes, the view says why already
                send(lines[0].text.clone());
                let mut on_wait = send.clone();
                maintenance::wait_after_reboot(manager, tokens, name, password, &mut on_wait)
                    .await?;
                lines.push(Line {
                    warn: false,
                    text: t!("shared.maintenance.back", name = name).to_string(),
                });
            }
            Ok(lines)
        }
    }
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
