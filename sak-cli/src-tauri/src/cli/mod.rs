//! The command line of sakctl. A command as the first argument (`args::Command`, `sakctl --help`)
//! runs without a window, on the same core and texts as the window. This module only parses the
//! arguments and prints. The Hetzner token comes from HCLOUD_TOKEN or is typed
//! (`--hcloud-token`). The name and the location of a deployment come from the argument or from
//! SAK_NAME and SAK_LOCATION. The password comes from SAK_PASSWORD or is typed (`--password`),
//! never from an argument. Every line it prints is `<date> <level> <message>`, the level in color
//! on a terminal. The exit code tells the failures apart (`core::error`)

mod args;
mod deploy;
mod log;
mod secrets;
mod server;
#[cfg(test)]
mod tests;

use crate::core::backup::BackupManager;
use crate::core::checks::{self, CheckEvent, ProviderTokensCheck};
use crate::core::container::ContainerRuntime;
use crate::core::deployments::{
    ADMIN_EMAIL_VARIABLE, AcmeCa, CreateEvent, DEFAULT_SMTP_PORT, DEFAULT_SMTP_STARTTLS,
    DeploymentManager, Intent, LOCATION_VARIABLE, LOCATIONS, NAME_VARIABLE, NewDeployment,
    PASSWORD_VARIABLE, PlanAction, ResourceStatus, SMTP_HOST_VARIABLE, SMTP_PASSWORD_VARIABLE,
    SMTP_PORT_VARIABLE, SMTP_STARTTLS_VARIABLE, SMTP_USERNAME_VARIABLE, Smtp, TaskOutcome,
    USER_MODE_VARIABLE, UserMode, Working, key, parse_flag,
};
use crate::core::error::{EXIT_OK, EXIT_UPDATE_AVAILABLE, EXIT_USAGE, WizardError};
use crate::core::providers::ProviderType;
use crate::core::update::{self, RECEIVER, Status};
use anstyle::{AnsiColor, Style};
use args::*;
pub use args::{Cli, Command};
use clap::{Args, Parser, Subcommand};
use deploy::*;
use log::*;
use rust_i18n::t;
use sak_cloud::hetzner::api::Location;
use sak_cloud::hetzner::deployment::DnsMode;
use secrets::*;
use server::*;
use std::collections::HashMap;
use std::fmt::Display;
use std::io::IsTerminal;

/// whether the arguments ask for the command line: a word (a command, a mistyped one too, which
/// clap answers with its usage error), help or the version as the first argument. Nothing, or
/// another flag (what a desktop passes, e.g. `-psn_…` on macOS), opens the window
pub fn is_command_line(args: &[String]) -> bool {
    match args.first().map(String::as_str) {
        Some("--help" | "-h" | "--version" | "-V") => true,
        Some(first) => !first.starts_with('-'),
        None => false,
    }
}

/// runs the command and gives the exit code of the process
pub fn run(command: Command) -> i32 {
    // ctrl+c stops a provisioning and removes the files of its run
    crate::core::provision::stop_runs_on_signal();
    // an update needs neither a token nor the checks
    let result = match command {
        Command::Update { check } => tauri::async_runtime::block_on(update(check)),
        Command::Project(command) => {
            tauri::async_runtime::block_on(execute(command)).map(|()| EXIT_OK)
        }
    };
    match result {
        Ok(code) => code,
        Err(error) => {
            log(Level::Error, &error.message);
            error.exit_code
        }
    }
}

/// compares sakctl with the newest release on github.com and, unless `check`, installs a newer one.
/// With `check` a newer release ends with `EXIT_UPDATE_AVAILABLE`
async fn update(check: bool) -> Result<i32, WizardError> {
    if !update::is_release() {
        log(Level::Info, Status::NoRelease.message());
        return Ok(EXIT_OK);
    }
    log(
        Level::Info,
        t!("shared.update.checking", receiver = RECEIVER),
    );
    let status = update::check().await?;
    log(Level::Info, status.message());
    let Status::Newer { newest, .. } = status else {
        return Ok(EXIT_OK);
    };
    if check {
        log(Level::Info, t!("cli.update_hint"));
        return Ok(EXIT_UPDATE_AVAILABLE);
    }
    log(
        Level::Info,
        t!(
            "shared.update.installing",
            version = newest,
            receiver = RECEIVER
        ),
    );
    let installed = update::install().await?;
    log(Level::Info, installed.installed_message());
    Ok(EXIT_OK)
}

/// the checks the window runs on startup, each one logged; the token checked is the one of the
/// command
async fn check(provider: &HetznerToken) -> Result<ContainerRuntime, String> {
    let tokens = ProviderTokensCheck::new(vec![(
        ProviderType::Hetzner,
        provider.0.clone(),
        "HCLOUD_TOKEN",
    )]);
    // the window shows the progress of a pull ten times a second; the log shows each tenth
    let mut logged = 0;
    let found = checks::run_all(tokens, move |event| {
        match event {
            CheckEvent::Started { id, .. } => {
                log(Level::Info, t!(format!("shared.checks.{id}.running")))
            }
            CheckEvent::Progress { fraction, text, .. } => {
                let tenth = (fraction * 10.0).floor() as u32;
                if tenth > logged && fraction < 1.0 {
                    logged = tenth;
                    log(Level::Info, text);
                }
            }
            CheckEvent::Passed { id, text, .. } => {
                logged = 0;
                log(
                    Level::Info,
                    t!(format!("shared.checks.{id}.done"), text = text),
                )
            }
            // the failure ends the command; `run` logs the error one time
            CheckEvent::Failed { .. } | CheckEvent::Listed { .. } | CheckEvent::Finished { .. } => {
            }
        }
        Ok(())
    })
    .await?;
    let runtime = ContainerRuntime::default();
    if let Some(found) = found.runtime {
        runtime.set(found);
    }
    Ok(runtime)
}

async fn execute(command: ProjectCommand) -> Result<(), WizardError> {
    let manager = DeploymentManager;
    let needs = command.needs();
    // asked before the checks, because they take some time
    let password = match needs.password {
        Some((ask, typed)) => password(ask, typed, password_variable())?,
        None => Default::default(),
    };
    let smtp_password = match needs.smtp_password {
        Some(ask) => smtp_password(ask, smtp_password_variable())?,
        None => String::new(),
    };
    let provider = needs.provider;
    let token = hcloud_token(provider.hcloud_token, std::env::var("HCLOUD_TOKEN").ok())?;
    let runtime = check(&token).await?;
    match command {
        ProjectCommand::List(_) => list(&manager, &token).await,
        ProjectCommand::Maintenance(command) => {
            maintenance(&manager, &token, &runtime, &command, &password.0).await
        }
        ProjectCommand::Config(ConfigCommand::Show { name, .. }) => {
            show_config(&manager, &token, &name, &password.0).await
        }
        ProjectCommand::Ssh { name, command, .. } => {
            ssh(&manager, &token, &runtime, &name, &password.0, &command).await
        }
        ProjectCommand::Backup { name, .. } => {
            backup(&manager, &token, &runtime, &name, &password.0).await
        }
        ProjectCommand::Restore {
            name, file, yes, ..
        } => restore(&manager, &token, &runtime, &name, &password.0, &file, yes).await,
        ProjectCommand::Create { deployment, .. } => {
            plan_and_apply(
                &manager,
                &token,
                &deployment.deployment(Intent::Create, false, password, &smtp_password),
                &runtime,
                deployment.show_log,
            )
            .await
        }
        ProjectCommand::Deploy { deployment, .. } => {
            // what is not given comes from the deployment, as the window does
            let new = manager
                .completed(
                    token.tokens(),
                    deployment.deployment(Intent::Deploy, false, password, &smtp_password),
                    &smtp_password,
                )
                .await?;
            plan_and_apply(&manager, &token, &new, &runtime, deployment.show_log).await
        }
        ProjectCommand::Destroy {
            deployment,
            delete_data,
            ..
        } => {
            plan_and_apply(
                &manager,
                &token,
                &deployment.deployment(Intent::Destroy, delete_data, password, ""),
                &runtime,
                deployment.show_log,
            )
            .await
        }
    }
}
