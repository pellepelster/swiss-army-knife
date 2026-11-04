//! the commands on the server of a deployment: a session, the maintenance, a backup, a restore
//! and its running configuration

use super::*;
use crate::core::deployments::{Detected, SettingsSource, UserMode};

/// what a command on the server needs: the name trimmed, the server and the keys to reach it (the
/// password checked first), and the container runtime ssh runs in
async fn session(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    runtime: &ContainerRuntime,
    name: &str,
    password: &str,
) -> Result<(String, crate::core::ssh::SshTarget, &'static str), SakctlError> {
    let name = name.trim().to_string();
    let target = manager
        .ssh_target(provider.tokens(), &name, password)
        .await?;
    let runtime = runtime
        .get()
        .ok_or_else(|| t!("common.provision.no_runtime").to_string())?;
    Ok((name, target, runtime))
}

/// a session on the server, or its command; its exit code, the one of the command on the server,
/// is the exit code of sakctl
pub(super) async fn ssh(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    runtime: &ContainerRuntime,
    name: &str,
    password: &str,
    command: &[String],
) -> Result<i32, SakctlError> {
    let (name, target, runtime) = session(manager, provider, runtime, name, password).await?;
    log(
        Level::Info,
        t!(
            "cli.ssh_connecting",
            name = name,
            host = target.host.as_str()
        ),
    );
    let command = command.to_vec();
    let code = tokio::task::spawn_blocking(move || {
        crate::core::ssh::open_session(runtime, &target, &command)
    })
    .await
    .map_err(|error| error.to_string())??;
    if code != 0 {
        log(Level::Info, t!("cli.ssh_ended", code = code));
    }
    Ok(code)
}

/// the maintenance of the server: each line of the server logged, the answer as lines or, with
/// --json, as it is. A reboot waits until the deployment answers again, unless --no-wait; an
/// update with --reboot-server waits too
pub(super) async fn maintenance(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    runtime: &ContainerRuntime,
    command: &MaintenanceCommand,
    password: &str,
) -> Result<(), SakctlError> {
    use crate::core::maintenance::{self, Action};
    let args = command.args();
    let name = args.name.trim();
    let json = args.json;
    let action = match command {
        MaintenanceCommand::Status(_) => Action::Status,
        MaintenanceCommand::Update {
            reboot_services,
            reboot_server,
            ..
        } => Action::Update {
            restart_services: *reboot_services,
            reboot_server: *reboot_server,
        },
        MaintenanceCommand::RestartServices(_) => Action::RestartServices,
        MaintenanceCommand::Reboot {
            if_pending,
            no_wait,
            ..
        } => Action::Reboot {
            if_pending: *if_pending,
            wait: !*no_wait,
        },
    };
    let on_line = move |line: String| {
        if !json {
            log(Level::Info, line)
        }
    };
    let answer = maintenance::perform(
        manager,
        provider.tokens(),
        runtime,
        name,
        password,
        action,
        on_line,
    )
    .await?;
    if json {
        return print_json(&maintenance_json(&answer));
    }
    let hint = t!("cli.maintenance.reboot_hint", name = name);
    for line in answer.lines(name, &hint) {
        log(if line.warn { Level::Warn } else { Level::Info }, line.text);
    }
    Ok(())
}

/// the answer of the server as it is; an update with what followed it below
/// `restart_services` or `reboot`
fn maintenance_json(answer: &crate::core::maintenance::Answer) -> serde_json::Value {
    use crate::core::maintenance::Answer;
    fn json(value: &impl serde::Serialize) -> serde_json::Value {
        serde_json::to_value(value).unwrap_or_default()
    }
    match answer {
        Answer::Status(status) => json(status),
        Answer::RestartServices(restart) => json(restart),
        Answer::Reboot { reboot, .. } => json(reboot),
        Answer::Update { update, then } => {
            let mut value = json(update);
            if let (Some(then), Some(object)) = (then, value.as_object_mut()) {
                let key = match **then {
                    Answer::RestartServices(_) => "restart_services",
                    _ => "reboot",
                };
                object.insert(key.to_string(), maintenance_json(then));
            }
            value
        }
    }
}

/// a backup of the deployment to `~/.sak/<name>/backups/`, each step of the server logged
pub(super) async fn backup(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    runtime: &ContainerRuntime,
    name: &str,
    password: &str,
) -> Result<(), SakctlError> {
    let (name, target, runtime) = session(manager, provider, runtime, name, password).await?;
    let key = crate::core::keys::backup_key(&name, password)?;
    let directory = crate::core::backup::directory(&name)?;
    log(
        Level::Info,
        t!(
            "cli.backup_started",
            name = name,
            host = target.host.as_str()
        ),
    );
    let path = directory.join(crate::core::backup::file_name(&name, chrono::Utc::now()));
    let backup = tokio::task::spawn_blocking(move || {
        backup::backup(runtime, &target, &key, &path, &mut |line| {
            log(Level::Info, line)
        })
    })
    .await
    .map_err(|error| error.to_string())??;
    log(
        Level::Info,
        t!(
            "cli.backup_done",
            name = name,
            path = backup.path.display(),
            size = size(backup.size)
        ),
    );
    Ok(())
}

/// replaces the data of the deployment with the backup in `file`, after the user typed the name
/// (or gave --yes)
pub(super) async fn restore(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    runtime: &ContainerRuntime,
    name: &str,
    password: &str,
    file: &std::path::Path,
    yes: bool,
) -> Result<(), SakctlError> {
    let (name, target, runtime) = session(manager, provider, runtime, name, password).await?;
    let key = crate::core::keys::backup_key(&name, password)?;
    if !yes {
        confirm_restore(&name, file)?;
    }
    log(
        Level::Info,
        t!(
            "cli.restore_started",
            name = name,
            host = target.host.as_str(),
            path = file.display()
        ),
    );
    let path = file.to_path_buf();
    tokio::task::spawn_blocking(move || {
        backup::restore(runtime, &target, &key, &path, &mut |line| {
            log(Level::Info, line)
        })
    })
    .await
    .map_err(|error| error.to_string())??;
    log(
        Level::Info,
        t!("cli.restore_done", name = name, path = file.display()),
    );
    Ok(())
}

/// prints the settings a redeploy of the deployment takes and where they come from, one line for
/// each setting
pub(super) async fn show_config(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    name: &str,
    password: &str,
) -> Result<(), SakctlError> {
    let detected = manager.detected(provider.tokens(), name, password).await?;
    for line in shown_lines(name, &detected) {
        log(Level::Info, line);
    }
    Ok(())
}

/// what `config show` prints: where the settings come from, then the settings; no secret
pub(super) fn shown_lines(name: &str, detected: &Detected) -> Vec<String> {
    let name = name.trim();
    let error = detected.unread.as_deref().unwrap_or_default();
    let source = match detected.source {
        Some(SettingsSource::Running) => t!("common.config.shown_running", name = name),
        Some(SettingsSource::Local) => t!(
            "common.config.shown_local",
            name = name,
            when = detected.deployed_at.as_deref().unwrap_or("-"),
            error = error
        ),
        Some(SettingsSource::Records) => {
            t!("common.config.shown_records", name = name, error = error)
        }
        None => t!("common.config.shown_none", name = name, error = error),
    };
    std::iter::once(source.to_string())
        .chain(config_lines(detected))
        .collect()
}

/// `dns_mode domain`, `smtp.host smtp.example.org`, … : the settings as `<key> <value>`, `-` for
/// one that is not known; the password of the mail server only as `set` or `-`
pub(super) fn config_lines(detected: &Detected) -> Vec<String> {
    let or_none = |value: Option<String>| value.unwrap_or_else(|| "-".to_string());
    let mut lines = vec![
        format!(
            "dns_mode {}",
            or_none(detected.dns_mode.map(|mode| mode.to_string()))
        ),
        format!("root_domain {}", or_none(detected.root_domain.clone())),
        format!(
            "acme_ca {}",
            or_none(detected.acme_ca.map(|ca| ca.to_string()))
        ),
        format!(
            "user_mode {}",
            or_none(detected.user_mode.map(|mode| mode.to_string()))
        ),
    ];
    if detected.user_mode == Some(UserMode::Multi) {
        lines.push(format!(
            "admin_email {}",
            or_none(detected.admin_email.clone())
        ));
        match &detected.smtp {
            None => lines.push(t!("common.config.no_smtp").to_string()),
            Some(smtp) => lines.extend([
                format!("smtp.host {}", smtp.host),
                format!("smtp.port {}", smtp.port),
                format!(
                    "smtp.username {}",
                    if smtp.username.is_empty() {
                        "-"
                    } else {
                        &smtp.username
                    }
                ),
                format!(
                    "smtp.password {}",
                    if smtp.password_set { "set" } else { "-" }
                ),
                format!("smtp.starttls {}", smtp.starttls),
                format!("smtp.from {}", smtp.from),
            ]),
        }
    }
    lines
}
