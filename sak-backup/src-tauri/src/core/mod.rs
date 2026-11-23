//! what the window and the command line of sakbackup share: the api of the instance for the
//! repositories and the backup plans, restic of this computer, the passwords of the repositories,
//! and the runs: backups, restores and the snapshots. Nothing here knows tauri's commands or the
//! terminal; gui/ and cli/ are thin on top of it

pub mod api;
pub mod checks;
pub mod excludes;
pub mod passwords;
pub mod presets;
pub mod restic;
pub mod restore;
pub mod run;
pub mod snapshots;

/// sakbackup as its release has it, what its update looks for (sak_desktop_shared::update)
pub const APP: sak_desktop_shared::update::App =
    sak_desktop_shared::update::App::new("sakbackup", "sak-backup");
