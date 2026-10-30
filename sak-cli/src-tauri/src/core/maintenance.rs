//! the maintenance of the server of a deployment: whether a reboot is pending, an update of all
//! packages, a coordinated reboot. `sak-backend` on the server does the work and answers as json
//! on its standard output; each line it writes to standard error goes to the caller while it runs

use crate::core::backup::SCRIPT;
use crate::core::ssh::{SshTarget, Stream, run_streaming};
use rust_i18n::t;
use serde::{Deserialize, Serialize};

/// the exit code of `sak-backend` when another backup, restore, update or reboot holds its lock
const BUSY: i32 = 2;

/// a package that changed: its version before (none for one installed new) and after
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct PackageChange {
    pub package: String,
    #[serde(default)]
    pub from: Option<String>,
    pub to: String,
    /// when dpkg changed it, for the upgrades since the boot
    #[serde(default)]
    pub time: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct Kernel {
    pub running: String,
    pub installed: String,
}

/// `sak-backend reboot-pending`
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct RebootStatus {
    pub reboot_pending: bool,
    /// `reboot-required` (a package asked for it), `kernel` (a newer one is installed)
    pub reasons: Vec<String>,
    pub kernel: Kernel,
    /// the services that still run old libraries
    pub services: Vec<String>,
    pub updates_since_boot: Vec<PackageChange>,
    pub booted_at: String,
}

/// `sak-backend update`
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct UpdateResult {
    pub upgraded: Vec<PackageChange>,
    pub installed: Vec<PackageChange>,
    pub reboot_pending: bool,
    pub reasons: Vec<String>,
}

/// `sak-backend reboot`
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct RebootResult {
    pub rebooting: bool,
    /// when the server goes down
    #[serde(default)]
    pub at: Option<String>,
    pub reasons: Vec<String>,
}

/// whether a reboot of the server is pending, why, and what dpkg upgraded since its boot
pub fn status(
    runtime: &str,
    target: &SshTarget,
    on_line: &mut (dyn FnMut(String) + Send),
) -> Result<RebootStatus, String> {
    run(runtime, target, &["reboot-pending"], on_line)
}

/// upgrades all packages of the server, none is removed
pub fn update(
    runtime: &str,
    target: &SshTarget,
    on_line: &mut (dyn FnMut(String) + Send),
) -> Result<UpdateResult, String> {
    run(runtime, target, &["update"], on_line)
}

/// stops the deployment in order and reboots the server; with `if_pending` only when a reboot
/// is pending
pub fn reboot(
    runtime: &str,
    target: &SshTarget,
    if_pending: bool,
    on_line: &mut (dyn FnMut(String) + Send),
) -> Result<RebootResult, String> {
    let mut command = vec!["reboot"];
    if if_pending {
        command.push("--if-pending");
    }
    run(runtime, target, &command, on_line)
}

fn run<T: for<'de> Deserialize<'de>>(
    runtime: &str,
    target: &SshTarget,
    command: &[&str],
    on_line: &mut (dyn FnMut(String) + Send),
) -> Result<T, String> {
    let command: Vec<String> = std::iter::once(SCRIPT)
        .chain(command.iter().copied())
        .map(String::from)
        .collect();
    let mut out = Vec::new();
    let code = run_streaming(runtime, target, &command, Stream::Out(&mut out), on_line)?;
    answer(code, &out)
}

/// the answer of the script: its json, or the error it answered, or what its exit code says
fn answer<T: for<'de> Deserialize<'de>>(code: i32, out: &[u8]) -> Result<T, String> {
    #[derive(Deserialize)]
    struct Failure {
        error: String,
    }
    if code == BUSY {
        return Err(t!("shared.maintenance.busy").to_string());
    }
    if code != 0 {
        if let Ok(failure) = serde_json::from_slice::<Failure>(out) {
            return Err(t!("shared.maintenance.failed", error = failure.error).to_string());
        }
        crate::core::backup::ended(code, SCRIPT)?;
    }
    serde_json::from_slice(out)
        .map_err(|error| t!("shared.maintenance.unreadable", error = error.to_string()).to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_answers_of_the_script_are_read() {
        let status: RebootStatus = answer(
            0,
            br#"{"reboot_pending": true, "reasons": ["kernel"],
                 "kernel": {"running": "6.12.38+deb13-amd64", "installed": "6.12.41+deb13-amd64"},
                 "services": ["dbus.service"],
                 "updates_since_boot": [{"package": "openssl", "from": "3.5.1-1", "to": "3.5.4-1", "time": "2026-10-30T03:12:44Z"}],
                 "booted_at": "2026-10-21T04:00:12Z"}"#,
        )
        .unwrap();
        assert!(status.reboot_pending);
        assert_eq!(
            status.updates_since_boot[0].from.as_deref(),
            Some("3.5.1-1")
        );

        let update: UpdateResult = answer(
            0,
            br#"{"upgraded": [], "installed": [{"package": "linux-image-6.12.41", "to": "6.12.41-1"}],
                 "reboot_pending": true, "reasons": ["kernel"]}"#,
        )
        .unwrap();
        assert_eq!(update.installed[0].from, None);

        let reboot: RebootResult = answer(0, br#"{"rebooting": false, "reasons": []}"#).unwrap();
        assert!(!reboot.rebooting);
    }

    #[test]
    fn a_busy_server_a_failure_and_a_missing_script_say_so() {
        let busy = answer::<RebootResult>(BUSY, b"").unwrap_err();
        assert_eq!(busy, t!("shared.maintenance.busy").to_string());
        let failed =
            answer::<UpdateResult>(1, br#"{"error": "apt-get update failed"}"#).unwrap_err();
        assert!(failed.contains("apt-get update failed"), "{failed}");
        let missing = answer::<RebootStatus>(127, b"").unwrap_err();
        assert_eq!(
            missing,
            t!("shared.backup.no_script", script = SCRIPT).to_string()
        );
        assert!(answer::<RebootStatus>(0, b"not json").is_err());
    }
}
