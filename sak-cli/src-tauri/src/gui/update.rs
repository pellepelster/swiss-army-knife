//! the commands of the footer about the version of sakctl and its update. The footer asks github
//! only when the user clicks, its button names github first

use crate::core::update::{self, RECEIVER, Status};
use serde::Serialize;

/// the version in the footer and who a check asks
#[derive(Serialize)]
pub struct VersionResponse {
    version: &'static str,
    receiver: &'static str,
}

/// what a check or an install found: the message to show, the newer version when there is one
#[derive(Serialize)]
pub struct UpdateResponse {
    message: String,
    newest: Option<String>,
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
    })
}

/// the new version runs from the next start of sakctl
#[tauri::command]
pub async fn install_update() -> Result<UpdateResponse, String> {
    let status = update::install().await?;
    Ok(UpdateResponse {
        message: status.installed_message(),
        newest: None,
    })
}
