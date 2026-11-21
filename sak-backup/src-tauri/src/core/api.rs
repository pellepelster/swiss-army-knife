//! the repositories and the backup plans of the user on the instance (BACKUP-1 to BACKUP-7), through
//! its api with the session of the app, and the host name of this computer. The token of a
//! repository is read only where restic needs it (restic.rs)

use sak_desktop_shared::api::{self, ApiError};
use sak_desktop_shared::session::SessionManager;
use serde_json::{Value, json};

/// the paths of the api of the repositories and the backup plans
pub(crate) const REPOSITORIES: &str = "restic/repositories";
pub(crate) const PLANS: &str = "restic/plans";

/// the host name of this computer: a backup plan of another one is shown, not changed (BACKUP-5)
pub fn host() -> String {
    gethostname::gethostname().to_string_lossy().into_owned()
}

/// the repositories of the user, by name, without their tokens
pub async fn repositories(sessions: &SessionManager) -> Result<Vec<Value>, ApiError> {
    api::all(sessions, REPOSITORIES).await
}

pub async fn create_repository(sessions: &SessionManager, name: &str) -> Result<Value, ApiError> {
    api::call(
        sessions,
        reqwest::Method::POST,
        REPOSITORIES,
        Some(&json!({ "name": name })),
    )
    .await
}

/// the backup plans of the user, of every computer, by name
pub async fn plans(sessions: &SessionManager) -> Result<Vec<Value>, ApiError> {
    api::all(sessions, PLANS).await
}

pub async fn create_plan(sessions: &SessionManager, plan: &Value) -> Result<Value, ApiError> {
    api::call(sessions, reqwest::Method::POST, PLANS, Some(plan)).await
}

pub async fn update_plan(
    sessions: &SessionManager,
    id: u64,
    plan: &Value,
) -> Result<Value, ApiError> {
    api::call(
        sessions,
        reqwest::Method::PUT,
        &format!("{PLANS}/{id}"),
        Some(plan),
    )
    .await
}

/// the item of `path` (repositories or plans) of the name; none when the user has none of it
pub async fn by_name(
    sessions: &SessionManager,
    path: &str,
    name: &str,
) -> Result<Option<Value>, ApiError> {
    Ok(api::all(sessions, path)
        .await?
        .into_iter()
        .find(|each| each["name"].as_str() == Some(name)))
}
