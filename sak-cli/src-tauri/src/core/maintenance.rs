//! the maintenance of the server of a deployment: whether a reboot is pending, an update of all
//! packages, a coordinated reboot. `sak-backend` on the server does the work and answers as json
//! on its standard output; each line it writes to standard error goes to the caller while it runs.
//! The answers as lines are the same on the command line and in the window

use crate::core::deployments::DeploymentManager;
use crate::core::error::SakctlError;
use crate::core::providers::ProviderType;
use crate::core::sak_backend;
use crate::core::ssh::{Sessions, SshTarget};
use rust_i18n::t;
use serde::{Deserialize, Serialize};
use std::time::Duration;

/// how long each command may take on the server. `sak-backend` waits up to 10 minutes for an apt
/// or dpkg that runs (`backend_apt_wait`) before an update or a reboot starts
const STATUS_TIMEOUT: Duration = Duration::from_secs(2 * 60);
const UPDATE_TIMEOUT: Duration = Duration::from_secs(45 * 60);
const REBOOT_TIMEOUT: Duration = Duration::from_secs(15 * 60);

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

/// why a reboot is pending, as `sak-backend` names it
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum Reason {
    /// a newer kernel is installed than the one running
    Kernel,
    /// a package asked for it (`/run/reboot-required`)
    RebootRequired,
    /// one a newer script names, shown as it is
    #[serde(untagged)]
    Other(String),
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
    pub reasons: Vec<Reason>,
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
    pub reasons: Vec<Reason>,
}

/// `sak-backend reboot`
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct RebootResult {
    pub rebooting: bool,
    /// when the server goes down
    #[serde(default)]
    pub at: Option<String>,
    pub reasons: Vec<Reason>,
}

/// whether a reboot of the server is pending, why, and what dpkg upgraded since its boot
fn status(
    runtime: &str,
    target: &SshTarget,
    on_line: &mut (dyn FnMut(String) + Send),
) -> Result<RebootStatus, String> {
    sak_backend::run_json(
        runtime,
        target,
        sak_backend::Command::RebootPending,
        on_line,
    )
}

/// upgrades all packages of the server, none is removed
fn update(
    runtime: &str,
    target: &SshTarget,
    on_line: &mut (dyn FnMut(String) + Send),
) -> Result<UpdateResult, String> {
    sak_backend::run_json(runtime, target, sak_backend::Command::Update, on_line)
}

/// stops the deployment in order and reboots the server; with `if_pending` only when a reboot
/// is pending
fn reboot(
    runtime: &str,
    target: &SshTarget,
    if_pending: bool,
    on_line: &mut (dyn FnMut(String) + Send),
) -> Result<RebootResult, String> {
    sak_backend::run_json(
        runtime,
        target,
        sak_backend::Command::Reboot { if_pending },
        on_line,
    )
}

/// `command`, one of the commands above, until it ends or `limit` passes: then its ssh session is
/// stopped, and with it the wait of sakctl; what the server started may still finish there. Only
/// its own session: a provisioning at the same time goes on
async fn limited<T: Send + 'static>(
    limit: Duration,
    command: impl FnOnce() -> Result<T, String> + Send + 'static,
) -> Result<T, String> {
    let sessions = Sessions::default();
    let running = sessions.clone();
    let command = tokio::task::spawn_blocking(move || running.run(command));
    match tokio::time::timeout(limit, command).await {
        Ok(done) => done.map_err(|error| error.to_string())?,
        Err(_) => {
            // the container goes with a process of the runtime, not on a worker of tokio
            let _ = tokio::task::spawn_blocking(move || sessions.stop()).await;
            Err(t!("shared.maintenance.timeout", minutes = limit.as_secs() / 60).to_string())
        }
    }
}

/// what a maintenance does on the server of a deployment
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action {
    Status,
    Update,
    /// `if_pending`: only when a reboot is pending; `wait`: until the deployment answers again
    Reboot {
        if_pending: bool,
        wait: bool,
    },
}

/// the answer of a maintenance as the server gave it
#[derive(Clone, Debug)]
pub enum Answer {
    Status(RebootStatus),
    Update(UpdateResult),
    /// `back`: the deployment answered again after the reboot
    Reboot {
        reboot: RebootResult,
        back: bool,
    },
}

impl Answer {
    /// the answer as lines; `reboot_hint` follows an update after which a reboot is pending, it
    /// names what reboots in the command line or in the window
    pub fn lines(&self, name: &str, reboot_hint: &str) -> Vec<Line> {
        match self {
            Answer::Status(status) => status_lines(name, status),
            Answer::Update(update) => {
                let mut lines = update_lines(name, update);
                if update.reboot_pending {
                    lines.push(Line::warn(reboot_hint));
                }
                lines
            }
            // the reboot itself went by while the deployment was waited for
            Answer::Reboot { back: true, .. } => {
                vec![Line::info(t!("shared.maintenance.back", name = name))]
            }
            Answer::Reboot { reboot, .. } => vec![reboot_line(name, reboot)],
        }
    }
}

/// the maintenance `action` on the server of the deployment `name`, its password checked first.
/// Each line of the server and each step goes to `on_line` while it runs; each command on the
/// server has its timeout (`limited`)
pub async fn perform(
    manager: &DeploymentManager,
    tokens: Vec<(ProviderType, String)>,
    container: &crate::core::container::ContainerRuntime,
    name: &str,
    password: &str,
    action: Action,
    on_line: impl FnMut(String) + Send + Clone + 'static,
) -> Result<Answer, SakctlError> {
    let target = manager.ssh_target(tokens.clone(), name, password).await?;
    let runtime = container
        .get()
        .ok_or_else(|| t!("shared.provision.no_runtime").to_string())?;
    let mut on_step = on_line.clone();
    on_step(
        t!(
            "shared.maintenance.connecting",
            name = name,
            host = target.host.as_str()
        )
        .to_string(),
    );
    let mut on_server = on_line;
    match action {
        Action::Status => Ok(Answer::Status(
            limited(STATUS_TIMEOUT, move || {
                status(runtime, &target, &mut on_server)
            })
            .await?,
        )),
        Action::Update => Ok(Answer::Update(
            limited(UPDATE_TIMEOUT, move || {
                update(runtime, &target, &mut on_server)
            })
            .await?,
        )),
        Action::Reboot { if_pending, wait } => {
            let reboot = limited(REBOOT_TIMEOUT, move || {
                reboot(runtime, &target, if_pending, &mut on_server)
            })
            .await?;
            if !(reboot.rebooting && wait) {
                return Ok(Answer::Reboot {
                    reboot,
                    back: false,
                });
            }
            // the wait takes minutes, what it waits for is said first
            on_step(reboot_line(name, &reboot).text);
            wait_after_reboot(manager, tokens, name, password, &mut on_step).await?;
            Ok(Answer::Reboot { reboot, back: true })
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
fn status_lines(name: &str, status: &RebootStatus) -> Vec<Line> {
    let mut lines = Vec::new();
    if status.reboot_pending {
        let reasons: Vec<String> = status
            .reasons
            .iter()
            .map(|reason| match reason {
                Reason::Kernel => t!(
                    "shared.maintenance.reason_kernel",
                    running = status.kernel.running.as_str(),
                    installed = status.kernel.installed.as_str()
                )
                .to_string(),
                Reason::RebootRequired => {
                    t!("shared.maintenance.reason_reboot_required").to_string()
                }
                Reason::Other(other) => other.clone(),
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
fn update_lines(name: &str, update: &UpdateResult) -> Vec<Line> {
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
fn reboot_line(name: &str, reboot: &RebootResult) -> Line {
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
async fn wait_after_reboot(
    manager: &DeploymentManager,
    tokens: Vec<(ProviderType, String)>,
    name: &str,
    password: &str,
    on_line: &mut (dyn FnMut(String) + Send),
) -> Result<(), SakctlError> {
    let endpoint = manager.public_endpoint(tokens, name, password).await?;
    // the server goes down some seconds after the answer; before that it answers no request,
    // the ingress stopped first
    tokio::time::sleep(Duration::from_secs(10)).await;
    on_line(
        t!(
            "shared.deployments.waiting_for_api",
            url = endpoint.url.as_str()
        )
        .to_string(),
    );
    crate::core::config::wait_for_api(
        &endpoint.url,
        endpoint.address,
        endpoint.trust,
        crate::core::config::API_WAIT,
        Duration::from_secs(5),
    )
    .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_answers_of_the_script_are_read() {
        let status: RebootStatus = serde_json::from_slice(br#"{"reboot_pending": true, "reasons": ["kernel"],
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

        let update: UpdateResult = serde_json::from_slice(br#"{"upgraded": [], "installed": [{"package": "linux-image-6.12.41", "to": "6.12.41-1"}],
                 "reboot_pending": true, "reasons": ["kernel"]}"#,
        )
        .unwrap();
        assert_eq!(update.installed[0].from, None);

        let reboot: RebootResult =
            serde_json::from_slice(br#"{"rebooting": false, "reasons": []}"#).unwrap();
        assert!(!reboot.rebooting);

        // the reasons by name, one the script names later as it is, and back to json the same
        let reasons: Vec<Reason> =
            serde_json::from_slice(br#"["kernel", "reboot-required", "firmware"]"#).unwrap();
        assert_eq!(
            reasons,
            [
                Reason::Kernel,
                Reason::RebootRequired,
                Reason::Other("firmware".to_string())
            ]
        );
        assert_eq!(
            serde_json::to_string(&reasons).unwrap(),
            r#"["kernel","reboot-required","firmware"]"#
        );
    }

    #[test]
    fn the_answers_read_as_lines_with_the_reasons_and_the_changes_of_packages() {
        let change = |package: &str, from: Option<&str>, to: &str| PackageChange {
            package: package.to_string(),
            from: from.map(String::from),
            to: to.to_string(),
            time: None,
        };
        let status = RebootStatus {
            reboot_pending: true,
            reasons: vec![Reason::Kernel],
            kernel: Kernel {
                running: "6.12.38".to_string(),
                installed: "6.12.41".to_string(),
            },
            services: vec!["dbus.service".to_string()],
            updates_since_boot: vec![change("openssl", Some("3.5.1-1"), "3.5.4-1")],
            booted_at: "2026-10-21T04:00:12Z".to_string(),
        };
        let lines: Vec<String> = Answer::Status(status)
            .lines("alpha", "HINT")
            .into_iter()
            .map(|line| line.text)
            .collect();
        assert_eq!(
            lines[0],
            "A reboot of alpha is pending: kernel 6.12.38 → 6.12.41."
        );
        assert_eq!(lines[1], "Services on old libraries: dbus.service");
        assert_eq!(lines.last().unwrap(), "openssl 3.5.1-1 → 3.5.4-1");

        let update = UpdateResult {
            upgraded: vec![change("openssl", Some("3.5.1-1"), "3.5.4-1")],
            installed: vec![change("linux-image-6.12.41", None, "6.12.41-1")],
            reboot_pending: true,
            reasons: vec![Reason::Kernel],
        };
        let lines: Vec<String> = Answer::Update(update)
            .lines("alpha", "HINT")
            .into_iter()
            .map(|line| line.text)
            .collect();
        assert_eq!(
            lines,
            [
                "openssl 3.5.1-1 → 3.5.4-1",
                "linux-image-6.12.41 6.12.41-1 (new)",
                "1 packages upgraded, 1 installed.",
                "HINT"
            ]
        );
        let nothing = UpdateResult {
            upgraded: vec![],
            installed: vec![],
            reboot_pending: false,
            reasons: vec![],
        };
        assert_eq!(
            Answer::Update(nothing).lines("alpha", "HINT")[0].text,
            "All packages of alpha are up-to-date."
        );

        // a reboot that was waited for ends with the deployment answering again; the reboot
        // itself went by as a step
        let reboot = RebootResult {
            rebooting: true,
            at: Some("2026-10-30T04:00:05Z".to_string()),
            reasons: vec![],
        };
        let waited = Answer::Reboot {
            reboot: reboot.clone(),
            back: true,
        };
        assert_eq!(
            waited.lines("alpha", "HINT")[0].text,
            "The deployment alpha answers again."
        );
        let not_waited = Answer::Reboot {
            reboot,
            back: false,
        };
        assert_eq!(
            not_waited.lines("alpha", "HINT")[0].text,
            "The deployment alpha is stopped, its server reboots at 2026-10-30T04:00:05Z."
        );
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

    #[tokio::test]
    async fn a_timeout_stops_the_session_of_its_command_not_another_run() {
        use crate::core::provision::{stopped, track_container};
        // a provisioning at the same time
        track_container("true", "sakctl-run-other");
        let (started, session) = std::sync::mpsc::channel();

        let late = limited(Duration::from_millis(50), move || {
            started
                .send(crate::core::ssh::tests::start_session("true"))
                .unwrap();
            std::thread::sleep(Duration::from_millis(500));
            Ok(())
        })
        .await;

        assert!(late.is_err());
        let session = session.recv().unwrap();
        assert!(stopped(&session));
        assert!(!stopped("sakctl-run-other"));
    }
}
