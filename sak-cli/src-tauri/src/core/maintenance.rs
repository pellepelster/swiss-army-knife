//! the maintenance of the server of a deployment: whether a reboot is pending, an update of all
//! packages, a coordinated reboot. `sak-backend` on the server does the work and answers as json
//! on its standard output; each line it writes to standard error goes to the caller while it runs.
//! The answers as lines are the same on the command line and in the window

use crate::core::backup::SCRIPT;
use crate::core::deployments::DeploymentManager;
use crate::core::providers::ProviderType;
use crate::core::ssh::{SshTarget, Stream, run_streaming};
use rust_i18n::t;
use serde::{Deserialize, Serialize};
use std::time::Duration;

/// the exit code of `sak-backend` when another backup, restore, update or reboot holds its lock
const BUSY: i32 = 2;

/// how long each command may take on the server. `sak-backend` waits up to 10 minutes for an apt
/// or dpkg that runs (`backend_apt_wait`) before an update or a reboot starts
pub const STATUS_TIMEOUT: Duration = Duration::from_secs(2 * 60);
pub const UPDATE_TIMEOUT: Duration = Duration::from_secs(45 * 60);
pub const REBOOT_TIMEOUT: Duration = Duration::from_secs(15 * 60);

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

/// `command`, one of the commands above, until it ends or `limit` passes: then its ssh session is
/// stopped, and with it the wait of sakctl; what the server started may still finish there
pub async fn limited<T: Send + 'static>(
    limit: Duration,
    command: impl FnOnce() -> Result<T, String> + Send + 'static,
) -> Result<T, String> {
    match tokio::time::timeout(limit, tokio::task::spawn_blocking(command)).await {
        Ok(done) => done.map_err(|error| error.to_string())?,
        Err(_) => {
            crate::core::provision::stop_runs();
            Err(t!("shared.maintenance.timeout", minutes = limit.as_secs() / 60).to_string())
        }
    }
}

/// a line of an answer; `warn` for one that asks for something to be done
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct Line {
    pub warn: bool,
    pub text: String,
}

impl Line {
    fn info(text: impl Into<String>) -> Self {
        Line {
            warn: false,
            text: text.into(),
        }
    }

    fn warn(text: impl Into<String>) -> Self {
        Line {
            warn: true,
            text: text.into(),
        }
    }
}

/// the answer of `status`: whether a reboot is pending and why, the services on old libraries,
/// the upgrades since the boot
pub fn status_lines(name: &str, status: &RebootStatus) -> Vec<Line> {
    let mut lines = Vec::new();
    if status.reboot_pending {
        let reasons: Vec<String> = status
            .reasons
            .iter()
            .map(|reason| match reason.as_str() {
                "kernel" => t!(
                    "shared.maintenance.reason_kernel",
                    running = status.kernel.running.as_str(),
                    installed = status.kernel.installed.as_str()
                )
                .to_string(),
                "reboot-required" => t!("shared.maintenance.reason_reboot_required").to_string(),
                other => other.to_string(),
            })
            .collect();
        lines.push(Line::warn(t!(
            "shared.maintenance.pending",
            name = name,
            reasons = reasons.join(", ")
        )));
    } else {
        lines.push(Line::info(t!(
            "shared.maintenance.not_pending",
            name = name
        )));
    }
    if !status.services.is_empty() {
        lines.push(Line::info(t!(
            "shared.maintenance.services",
            services = status.services.join(", ")
        )));
    }
    if !status.updates_since_boot.is_empty() {
        lines.push(Line::info(t!(
            "shared.maintenance.since_boot",
            time = status.booted_at.as_str()
        )));
        lines.extend(
            status
                .updates_since_boot
                .iter()
                .map(|change| Line::info(change_line(change))),
        );
    }
    lines
}

/// the answer of `update`: each package that changed and how many; what to do about a pending
/// reboot is up to the caller
pub fn update_lines(name: &str, update: &UpdateResult) -> Vec<Line> {
    let mut lines: Vec<Line> = update
        .upgraded
        .iter()
        .chain(&update.installed)
        .map(|change| Line::info(change_line(change)))
        .collect();
    lines.push(Line::info(if lines.is_empty() {
        t!("shared.maintenance.up_to_date", name = name)
    } else {
        t!(
            "shared.maintenance.updated",
            upgraded = update.upgraded.len(),
            installed = update.installed.len()
        )
    }));
    lines
}

/// the answer of `reboot`: when the server goes down, or that nothing was changed
pub fn reboot_line(name: &str, reboot: &RebootResult) -> Line {
    if reboot.rebooting {
        Line::info(t!(
            "shared.maintenance.rebooting",
            name = name,
            at = reboot.at.as_deref().unwrap_or_default()
        ))
    } else {
        Line::info(t!("shared.maintenance.reboot_not_pending", name = name))
    }
}

/// `openssl 3.5.1-1 → 3.5.4-1`, or `linux-image-6.12.41 6.12.41-1 (new)`
fn change_line(change: &PackageChange) -> String {
    match &change.from {
        Some(from) => format!("{} {from} → {}", change.package, change.to),
        None => t!(
            "shared.maintenance.installed_new",
            package = change.package.as_str(),
            to = change.to.as_str()
        )
        .to_string(),
    }
}

/// waits until the deployment answers at its public endpoint again, after a reboot of its server;
/// the wait goes to `on_line`
pub async fn wait_after_reboot(
    manager: &DeploymentManager,
    tokens: Vec<(ProviderType, String)>,
    name: &str,
    password: &str,
    on_line: &mut (dyn FnMut(String) + Send),
) -> Result<(), String> {
    let (api, trust, address) = manager.public_endpoint(tokens, name, password).await?;
    // the server goes down some seconds after the answer; before that it answers no request,
    // the ingress stopped first
    tokio::time::sleep(Duration::from_secs(10)).await;
    on_line(t!("shared.deployments.waiting_for_api", url = api.as_str()).to_string());
    crate::core::config::wait_for_api(
        &api,
        address,
        trust,
        crate::core::config::API_WAIT,
        Duration::from_secs(5),
    )
    .await
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

    #[tokio::test]
    async fn a_command_ends_with_its_answer_or_at_its_timeout() {
        let answer = limited(Duration::from_secs(5), || Ok(7)).await;
        assert_eq!(answer, Ok(7));
        let failed = limited(Duration::from_secs(5), || {
            Err::<i32, _>("failed".to_string())
        })
        .await;
        assert_eq!(failed, Err("failed".to_string()));
        let late = limited(Duration::from_millis(50), || {
            std::thread::sleep(Duration::from_millis(500));
            Ok(7)
        })
        .await;
        assert_eq!(
            late,
            Err(t!("shared.maintenance.timeout", minutes = 0).to_string())
        );
    }
}
