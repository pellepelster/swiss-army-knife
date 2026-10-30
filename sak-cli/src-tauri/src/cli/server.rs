//! the commands on the server of a deployment: a session, the maintenance, a backup, a restore
//! and its running configuration

use super::*;

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
        .ok_or_else(|| t!("shared.provision.no_runtime").to_string())?;
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
/// --json, as it is. A reboot waits until the deployment answers again, unless --no-wait
pub(super) async fn maintenance(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    runtime: &ContainerRuntime,
    command: &MaintenanceCommand,
    password: &str,
) -> Result<(), SakctlError> {
    use crate::core::maintenance::{self, Action, Answer};
    let args = command.args();
    let name = args.name.trim();
    let json = args.json;
    let action = match command {
        MaintenanceCommand::Status(_) => Action::Status,
        MaintenanceCommand::Update(_) => Action::Update,
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
        return match &answer {
            Answer::Status(status) => print_json(status),
            Answer::Update(update) => print_json(update),
            Answer::Reboot { reboot, .. } => print_json(reboot),
        };
    }
    let hint = t!("cli.maintenance.reboot_hint", name = name);
    for line in answer.lines(name, &hint) {
        log(if line.warn { Level::Warn } else { Level::Info }, line.text);
    }
    Ok(())
}

/// a backup of the deployment to `~/.sak/backups/<name>/`, each step of the server logged
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

/// prints the running configuration of the deployment, one line for each setting
pub(super) async fn show_config(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    name: &str,
    password: &str,
) -> Result<(), SakctlError> {
    let (url, config) = manager
        .running_config(provider.tokens(), name, password)
        .await?;
    for line in shown_lines(name, &url, &config) {
        log(Level::Info, line);
    }
    Ok(())
}

/// what `config show` prints: where it asked, then the settings; no secret
pub(super) fn shown_lines(
    name: &str,
    url: &str,
    config: &crate::core::config::RunningConfig,
) -> Vec<String> {
    std::iter::once(t!("shared.config.shown", name = name.trim(), url = url).to_string())
        .chain(config_lines(config))
        .collect()
}

/// `smtp.host smtp.example.org`, … : the settings as `<key> <value>`; the password of the mail
/// server only as `set` or `-`
pub(super) fn config_lines(config: &crate::core::config::RunningConfig) -> Vec<String> {
    let Some(smtp) = &config.smtp else {
        return vec![t!("shared.config.no_smtp").to_string()];
    };
    vec![
        format!("smtp.host {}", smtp.host),
        format!("smtp.port {}", smtp.port),
        format!("smtp.username {}", smtp.username.as_deref().unwrap_or("-")),
        format!(
            "smtp.password {}",
            if smtp.password_set { "set" } else { "-" }
        ),
        format!("smtp.from {}", smtp.from),
        format!("smtp.tls {}", smtp.tls),
        format!("smtp.starttls {}", smtp.starttls),
    ]
}
