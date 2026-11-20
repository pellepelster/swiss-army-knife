//! the commands of the page of the repositories of restic (BACKUP-1 to BACKUP-6): the api of the
//! instance with the session of the app. The values go through as the api has them; the page
//! never sees the secrets of a connection

use sak_desktop_shared::api::{self, ApiError};
use sak_desktop_shared::session::SessionManager;
use serde_json::Value;
use tauri::State;

const REPOSITORIES: &str = "restic/repositories";

/// the repositories of the user and the shared ones of the others, by name
#[tauri::command]
pub async fn repositories(sessions: State<'_, SessionManager>) -> Result<Vec<Value>, ApiError> {
    api::all(&sessions, REPOSITORIES).await
}

#[tauri::command]
pub async fn repository(sessions: State<'_, SessionManager>, id: u32) -> Result<Value, ApiError> {
    api::call(
        &sessions,
        reqwest::Method::GET,
        &format!("{REPOSITORIES}/{id}"),
        None,
    )
    .await
}

#[tauri::command]
pub async fn create_repository(
    sessions: State<'_, SessionManager>,
    repository: Value,
) -> Result<Value, ApiError> {
    api::call(
        &sessions,
        reqwest::Method::POST,
        REPOSITORIES,
        Some(&repository),
    )
    .await
}

/// replaces the values of a repository of the user; a secret given as kept keeps its value
#[tauri::command]
pub async fn update_repository(
    sessions: State<'_, SessionManager>,
    id: u32,
    repository: Value,
) -> Result<Value, ApiError> {
    api::call(
        &sessions,
        reqwest::Method::PUT,
        &format!("{REPOSITORIES}/{id}"),
        Some(&repository),
    )
    .await
}
