//! the commands of the pages: each one the function of core/ with the state of the window, and the
//! dialogs of the system that pick paths. The token of a repository never comes to the page
//! (BACKUP-2), nor the password of SAK_BACKUP_REPO_PASSWORD or a kept one (BACKUP-3)

use crate::core::passwords::{Given, PasswordStore};
use crate::core::run::{RunError, RunEvent, RunSummary, Runs};
use crate::core::snapshots::Snapshots;
use crate::core::{api, excludes, passwords, presets, restore, run, snapshots};
use rust_i18n::t;
use sak_desktop_shared::api::ApiError;
use sak_desktop_shared::checks::{self, CheckEvent};
use sak_desktop_shared::session::SessionManager;
use serde::Serialize;
use serde_json::Value;
use std::path::PathBuf;
use tauri::State;
use tauri::ipc::Channel;
use tauri_plugin_dialog::DialogExt;

#[tauri::command]
pub fn host() -> String {
    api::host()
}

#[tauri::command]
pub async fn repositories(sessions: State<'_, SessionManager>) -> Result<Vec<Value>, ApiError> {
    api::repositories(&sessions).await
}

#[tauri::command]
pub async fn create_repository(
    sessions: State<'_, SessionManager>,
    name: String,
) -> Result<Value, ApiError> {
    api::create_repository(&sessions, &name).await
}

#[tauri::command]
pub async fn plans(sessions: State<'_, SessionManager>) -> Result<Vec<Value>, ApiError> {
    api::plans(&sessions).await
}

#[tauri::command]
pub async fn create_plan(
    sessions: State<'_, SessionManager>,
    plan: Value,
) -> Result<Value, ApiError> {
    api::create_plan(&sessions, &plan).await
}

#[tauri::command]
pub async fn update_plan(
    sessions: State<'_, SessionManager>,
    id: u32,
    plan: Value,
) -> Result<Value, ApiError> {
    api::update_plan(&sessions, u64::from(id), &plan).await
}

/// deletes a backup plan of this computer (BACKUP-5); its repository and its snapshots stay
#[tauri::command]
pub async fn delete_plan(sessions: State<'_, SessionManager>, id: u32) -> Result<(), ApiError> {
    api::delete_plan(&sessions, u64::from(id)).await
}

/// folders or files the user picks in the dialog of the system, as absolute paths; none when the
/// dialog is cancelled
#[tauri::command]
pub async fn pick_paths(app: tauri::AppHandle, folders: bool) -> Result<Vec<String>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let dialog = app.dialog().file();
        let picked = if folders {
            dialog.blocking_pick_folders()
        } else {
            dialog.blocking_pick_files()
        };
        picked
            .unwrap_or_default()
            .into_iter()
            .filter_map(|path| path.into_path().ok())
            .map(|path| path.to_string_lossy().into_owned())
            .collect()
    })
    .await
    .map_err(|error| error.to_string())
}

/// a folder the user picks in the dialog of the system, e.g. to restore into; none when the dialog
/// is cancelled
#[tauri::command]
pub async fn pick_folder(app: tauri::AppHandle) -> Result<Option<String>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .blocking_pick_folder()
            .and_then(|path| path.into_path().ok())
            .map(|path| path.to_string_lossy().into_owned())
    })
    .await
    .map_err(|error| error.to_string())
}

/// a backup of the plan, its progress to the page; `password` none for the one of
/// SAK_BACKUP_REPO_PASSWORD or the kept one
// the page names each argument, a command of tauri takes them one by one
#[allow(clippy::too_many_arguments)]
#[tauri::command]
pub async fn run_backup(
    sessions: State<'_, SessionManager>,
    runs: State<'_, Runs>,
    passwords: State<'_, PasswordStore>,
    id: u32,
    password: Option<String>,
    init: bool,
    remember: bool,
    on_event: Channel<RunEvent>,
) -> Result<RunSummary, RunError> {
    // no restart of sakbackup meanwhile (restart)
    let _busy = runs.busy();
    let given = Given {
        typed: password,
        remember,
    };
    run::back_up(
        &sessions,
        &runs,
        &passwords,
        u64::from(id),
        given,
        init,
        move |event| {
            let _ = on_event.send(event);
        },
    )
    .await
}

#[tauri::command]
pub fn cancel_backup(runs: State<'_, Runs>) {
    run::cancel(&runs);
}

/// the checks of core on the splash screen (sak_desktop_shared::checks): each event goes to the
/// splash screen. A failure shows there, in the row of its check; then the user can only quit
#[tauri::command]
pub async fn run_checks(on_event: Channel<CheckEvent<()>>) -> Result<(), String> {
    let channel = on_event.clone();
    checks::run_all(crate::core::checks::checks(), move |event| {
        checks::send(&channel, event)
    })
    .await
    .inspect_err(|text| eprintln!("{text}"))?;
    checks::send(&on_event, CheckEvent::Finished { next: () })
}

/// the presets of excludes of this computer, each with its name and its patterns (core/presets.rs)
#[tauri::command]
pub fn exclude_presets() -> Vec<presets::Preset> {
    presets::presets(presets::System::this())
}

/// the excludes of a file the user picked, e.g. one of restic of before; at most the size a plan
/// takes
#[tauri::command]
pub fn read_excludes(path: String) -> Result<String, String> {
    let bytes = std::fs::read(&path).map_err(|error| error.to_string())?;
    if bytes.len() > MAX_EXCLUDES_BYTES {
        return Err(t!("ui.excludes.file_too_large", max = MAX_EXCLUDES_BYTES).to_string());
    }
    String::from_utf8(bytes).map_err(|_| t!("ui.excludes.file_not_text").to_string())
}

/// the bytes of excludes a backup plan takes (ResticBackupPlanService of sak-backend)
const MAX_EXCLUDES_BYTES: usize = 65536;

/// what a run of the paths would leave out with the excludes, on this computer, by the rules of
/// restic; walks the paths on a thread of its own, at most for its limits
#[tauri::command]
pub async fn preview_excludes(
    paths: Vec<String>,
    excludes: String,
    exclude_caches: bool,
) -> Result<excludes::Preview, String> {
    let excludes = excludes::Excludes {
        patterns: excludes,
        caches: exclude_caches,
    };
    tauri::async_runtime::spawn_blocking(move || {
        excludes::preview(&paths, &excludes, excludes::Limits::default())
    })
    .await
    .map_err(|error| error.to_string())
}

/// starts sakbackup again, the binary it was started from, which an update replaced
/// (sak_desktop_shared::update). Refused while a backup, a restore or a delete of a snapshot goes
/// on (`Runs::busy`), a restart would stop it
#[tauri::command]
pub fn restart(app: tauri::AppHandle, runs: State<'_, Runs>) -> Result<(), String> {
    if runs.is_running() {
        return Err(t!("ui.update.restart_while_running").to_string());
    }
    app.restart()
}

/// how a restore ended: the files restic could not write, each with why; it restored the others
#[derive(Clone, Debug, Serialize)]
pub struct RestoreSummary {
    pub skipped: Vec<String>,
}

/// restores the plan below `target`, an absolute folder; none puts the files back where they were
#[tauri::command]
pub async fn restore_backup(
    sessions: State<'_, SessionManager>,
    runs: State<'_, Runs>,
    passwords: State<'_, PasswordStore>,
    id: u32,
    target: Option<String>,
    password: Option<String>,
    remember: bool,
) -> Result<RestoreSummary, RunError> {
    // no restart of sakbackup meanwhile (restart)
    let _busy = runs.busy();
    let target = PathBuf::from(target.unwrap_or_else(|| "/".to_string()));
    let given = Given {
        typed: password,
        remember,
    };
    restore::restore(&sessions, &passwords, u64::from(id), target, given)
        .await
        .map(|skipped| RestoreSummary { skipped })
}

#[tauri::command]
pub async fn snapshots(
    sessions: State<'_, SessionManager>,
    passwords: State<'_, PasswordStore>,
    repository_id: u64,
    password: Option<String>,
    remember: bool,
) -> Result<Snapshots, RunError> {
    let given = Given {
        typed: password,
        remember,
    };
    snapshots::snapshots(&sessions, &passwords, repository_id, given).await
}

#[tauri::command]
pub async fn delete_snapshot(
    sessions: State<'_, SessionManager>,
    runs: State<'_, Runs>,
    passwords: State<'_, PasswordStore>,
    repository_id: u64,
    snapshot_id: String,
    password: Option<String>,
    remember: bool,
) -> Result<(), RunError> {
    // no restart of sakbackup meanwhile (restart)
    let _busy = runs.busy();
    let given = Given {
        typed: password,
        remember,
    };
    snapshots::delete(&sessions, &passwords, repository_id, snapshot_id, given).await
}

/// whether SAK_BACKUP_REPO_PASSWORD gives the password: the page asks for none then, it never sees
/// it
#[tauri::command]
pub fn env_password_set() -> bool {
    passwords::env_password().is_some()
}

#[tauri::command]
pub async fn password_kept(
    sessions: State<'_, SessionManager>,
    passwords: State<'_, PasswordStore>,
    repository_id: u64,
) -> Result<bool, RunError> {
    passwords::kept(&sessions, &passwords, repository_id).await
}

#[tauri::command]
pub async fn forget_password(
    sessions: State<'_, SessionManager>,
    passwords: State<'_, PasswordStore>,
    repository_id: u64,
) -> Result<(), RunError> {
    passwords::forget(&sessions, &passwords, repository_id).await
}
