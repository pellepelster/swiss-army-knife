//! the commands of the footer about the version of sakctl and its update. The footer asks github
//! only when the user clicks, its button names github first; after an update it restarts sakctl
//! with the new version

use crate::core::update::{self, RECEIVER, Status};
use crate::gui::state::RunningWork;
use rust_i18n::t;
use serde::Serialize;
use tauri::{AppHandle, State};

/// the version in the footer and who a check asks
#[derive(Serialize)]
pub struct VersionResponse {
    version: &'static str,
    receiver: &'static str,
}

/// what a check or an install found: the message to show, the newer version when there is one,
/// and whether an install replaced sakctl, so a restart starts the new version
#[derive(Serialize)]
pub struct UpdateResponse {
    message: String,
    newest: Option<String>,
    installed: bool,
}

#[tauri::command]
pub fn version() -> VersionResponse {
    VersionResponse {
        version: crate::core::VERSION,
        receiver: RECEIVER,
    }
}

#[tauri::command]
pub async fn check_update() -> Result<UpdateResponse, String> {
    let status = update::check().await?;
    let newest = match &status {
        Status::Newer { newest, .. } => Some(newest.clone()),
        _ => None,
    };
    Ok(UpdateResponse {
        message: status.message(),
        newest,
        installed: false,
    })
}

/// the new version runs from the next start of sakctl
#[tauri::command]
pub async fn install_update() -> Result<UpdateResponse, String> {
    let status = update::install().await?;
    let newest = match &status {
        Status::Newer { newest, .. } => Some(newest.clone()),
        _ => None,
    };
    Ok(UpdateResponse {
        message: status.installed_message(),
        installed: newest.is_some(),
        newest,
    })
}

/// starts sakctl anew, the binary it was started from, which an install replaced. Refused while a
/// deployment is changed, a restart would stop it halfway; a provisioning left is stopped first
#[tauri::command]
pub fn restart(app: AppHandle, running: State<'_, RunningWork>) -> Result<(), String> {
    if running.is_running() {
        return Err(t!("ui.update.restart_while_running").to_string());
    }
    crate::core::provision::stop_runs();
    app.restart()
}
