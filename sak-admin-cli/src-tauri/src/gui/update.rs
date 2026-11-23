//! the restart of sakadmin after an update. The version in the footer and the update itself are the
//! commands of sak_desktop_shared::update, for `core::APP`

use crate::gui::state::RunningWork;
use rust_i18n::t;
use tauri::{AppHandle, State};

/// starts sakadmin again, the binary it was started from, which an install replaced. Refused while a
/// deployment is changed, a restart would stop it halfway; a provisioning left is stopped first
#[tauri::command]
pub fn restart(app: AppHandle, running: State<'_, RunningWork>) -> Result<(), String> {
    if running.is_running() {
        return Err(t!("ui.update.restart_while_running").to_string());
    }
    crate::core::provision::stop_runs();
    app.restart()
}
