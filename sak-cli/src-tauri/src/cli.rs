//! The command line of sakctl. A command as the first argument (`list`, `create`, `deploy`,
//! `destroy`, `config`, `ssh`, `backup`, `restore`, `update`) runs without a window, on the same core and texts as the window. This file
//! only parses the arguments and prints. The Hetzner token comes from HCLOUD_TOKEN or is typed
//! (`--hcloud-token`). The name and the location of a deployment come from the argument or from
//! SAK_NAME and SAK_LOCATION. The password comes from SAK_PASSWORD or is typed (`--password`),
//! never from an argument. Every line it prints is `<date> <level> <message>`, the level in color
//! on a terminal. The exit code tells the failures apart (`core::error`)

use crate::core::backup::BackupManager;
use crate::core::checks::{self, CheckEvent, ProviderTokensCheck};
use crate::core::container::ContainerRuntime;
use crate::core::deployments::{
    ADMIN_EMAIL_VARIABLE, AcmeCa, CreateEvent, DEFAULT_SMTP_PORT, DEFAULT_SMTP_STARTTLS,
    DeploymentManager, Intent, LOCATION_VARIABLE, LOCATIONS, NAME_VARIABLE, NewDeployment,
    PASSWORD_VARIABLE, SMTP_HOST_VARIABLE, SMTP_PASSWORD_VARIABLE, SMTP_PORT_VARIABLE,
    SMTP_STARTTLS_VARIABLE, SMTP_USERNAME_VARIABLE, Smtp, TaskOutcome, USER_MODE_VARIABLE,
    UserMode, applied_key, complete, destroyed_key, key, parse_flag, resource_status,
};
use crate::core::error::{EXIT_OK, EXIT_UPDATE_AVAILABLE, EXIT_USAGE, WizardError};
use crate::core::providers::ProviderType;
use crate::core::update::{self, RECEIVER, Status};
use anstyle::{AnsiColor, Style};
use clap::{Args, Parser, Subcommand};
use rust_i18n::t;
use sak_cloud::hetzner::api::Location;
use sak_cloud::hetzner::deployment::DnsMode;
use std::collections::HashMap;
use std::fmt::Display;
use std::io::IsTerminal;

/// the exit codes at the end of the help; a test makes sure they are those of `core::error`
const EXIT_CODES: &str = "Exit codes:
  0  The command did what it should.
  1  The command failed.
  2  The arguments are wrong, e.g. a name that is none.
  3  Domain mode asked, another deployment manages the root domain in domain mode already.
  4  The DNS mode asked is not the one other deployments use the root domain in: a root
     domain has one domain deployment or only subdomain deployments.
  5  A resource of a name the deployment uses belongs to someone else: it lacks the label
     sak.pelle.io/name or the password hash of the deployment. Nothing of it was changed.
  6  The data volume or a primary IP would have to be created anew, which would remove its
     data or address. Nothing was changed.
  7  update --check: a newer release of sakctl exists.";

/// Swiss Army Knife: manage the deployments of a Hetzner Cloud project. Without a command the
/// window opens.
#[derive(Parser, Debug)]
#[command(
    name = "sakctl",
    version = crate::core::VERSION,
    disable_help_subcommand = true,
    after_help = EXIT_CODES
)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Option<Command>,
}

#[derive(Subcommand, Debug, PartialEq, Eq)]
pub enum Command {
    /// List the deployments and the state of their resources
    #[command(after_help = EXIT_CODES)]
    List(Provider),
    /// Create a deployment and provision its server
    ///
    /// Creates an ssh key, a data volume, a primary IPv4 and IPv6, a firewall, the server and its
    /// DNS records.
    #[command(after_help = EXIT_CODES)]
    Create {
        #[command(flatten)]
        provider: Provider,
        #[command(flatten)]
        deployment: DeploymentArgs,
    },
    /// Update a deployment and provision its server again
    ///
    /// Creates the server anew only when it must. The data volume and the addresses stay.
    #[command(after_help = EXIT_CODES)]
    Deploy {
        #[command(flatten)]
        provider: Provider,
        #[command(flatten)]
        deployment: DeploymentArgs,
    },
    /// Show the configuration of a running deployment
    #[command(subcommand)]
    Config(ConfigCommand),
    /// Open an ssh session on the server, or run a command after --
    ///
    /// Runs in the image of the tools with the key derived from the password, and checks the
    /// server's derived host key. The key is never written to disk.
    #[command(after_help = EXIT_CODES)]
    Ssh {
        #[command(flatten)]
        provider: Provider,
        /// The name of the deployment
        #[arg(env = NAME_VARIABLE)]
        name: String,
        /// Ask for the password, even when SAK_PASSWORD is set
        #[arg(long)]
        password: bool,
        /// A command to run on the server instead of a session
        #[arg(last = true)]
        command: Vec<String>,
    },
    /// Back up all data of a deployment
    ///
    /// The server compresses the data and encrypts it with a key derived from the password, to
    /// ~/.sak/backups/<name>/<name>-<UTC time>.age.sak.
    #[command(after_help = EXIT_CODES)]
    Backup {
        #[command(flatten)]
        provider: Provider,
        /// The name of the deployment
        #[arg(env = NAME_VARIABLE)]
        name: String,
        /// Ask for the password, even when SAK_PASSWORD is set
        #[arg(long)]
        password: bool,
    },
    /// Replace all data of a deployment with a backup
    ///
    /// The deployment must exist. The files of its provisioning stay.
    #[command(after_help = EXIT_CODES)]
    Restore {
        #[command(flatten)]
        provider: Provider,
        /// The name of the deployment
        #[arg(env = NAME_VARIABLE)]
        name: String,
        /// The backup, a file that `sakctl backup` wrote
        file: std::path::PathBuf,
        /// Ask for the password, even when SAK_PASSWORD is set
        #[arg(long)]
        password: bool,
        /// Replace the data without asking
        #[arg(long)]
        yes: bool,
    },
    /// Destroy a deployment, keeping its data volume
    ///
    /// Removes its DNS records, server, addresses, firewall and ssh key. --delete-data also
    /// deletes the data volume. A deployment that is already gone counts as destroyed.
    #[command(after_help = EXIT_CODES)]
    Destroy {
        #[command(flatten)]
        provider: Provider,
        #[command(flatten)]
        deployment: DeploymentArgs,
        /// Delete the data volume too, with everything stored on it
        #[arg(long)]
        delete_data: bool,
    },
    /// Maintain the server of a deployment: pending reboot, package updates, reboot
    #[command(subcommand)]
    Maintenance(MaintenanceCommand),
    /// Update sakctl to the newest release from github.com
    ///
    /// Replaces sakctl only when the release is newer, after checking its sha-256.
    #[command(after_help = EXIT_CODES)]
    Update {
        /// Only check for a newer release, exit code 7 when there is one
        #[arg(long)]
        check: bool,
    },
}

#[derive(Subcommand, Debug, PartialEq, Eq)]
pub enum MaintenanceCommand {
    /// Show whether a reboot is pending and what was upgraded since the boot
    #[command(after_help = EXIT_CODES)]
    Status(MaintenanceArgs),
    /// Upgrade all packages of the server
    ///
    /// Installs new dependencies, removes no package. Tells what changed and whether a reboot is
    /// pending.
    #[command(after_help = EXIT_CODES)]
    Update(MaintenanceArgs),
    /// Reboot the server, stopping the deployment first
    ///
    /// Stops the deployment in order, waits for a backup or update that runs, reboots the server
    /// and waits until the deployment answers again.
    #[command(after_help = EXIT_CODES)]
    Reboot {
        #[command(flatten)]
        args: MaintenanceArgs,
        /// Reboot only when a reboot is pending
        #[arg(long)]
        if_pending: bool,
        /// Do not wait until the deployment answers again
        #[arg(long)]
        no_wait: bool,
    },
}

#[derive(Args, Debug, PartialEq, Eq)]
pub struct MaintenanceArgs {
    #[command(flatten)]
    provider: Provider,
    /// The name of the deployment
    #[arg(env = NAME_VARIABLE)]
    name: String,
    /// Ask for the password, even when SAK_PASSWORD is set
    #[arg(long)]
    password: bool,
    /// Print the answer of the server as JSON
    #[arg(long)]
    json: bool,
}

impl MaintenanceCommand {
    fn args(&self) -> &MaintenanceArgs {
        match self {
            MaintenanceCommand::Status(args)
            | MaintenanceCommand::Update(args)
            | MaintenanceCommand::Reboot { args, .. } => args,
        }
    }
}

#[derive(Subcommand, Debug, PartialEq, Eq)]
pub enum ConfigCommand {
    /// Show the configuration of a running deployment
    ///
    /// Reads it from the backend with a request signed with the key derived from the password.
    /// No secret is shown.
    #[command(after_help = EXIT_CODES)]
    Show {
        #[command(flatten)]
        provider: Provider,
        /// The name of the deployment
        #[arg(env = NAME_VARIABLE)]
        name: String,
        /// Ask for the password, even when SAK_PASSWORD is set
        #[arg(long)]
        password: bool,
    },
}

#[derive(Args, Debug, PartialEq, Eq)]
pub struct Provider {
    /// Ask for the Hetzner Cloud API token, even when HCLOUD_TOKEN is set
    ///
    /// The token is never an argument: it comes from HCLOUD_TOKEN or is typed without echo.
    #[arg(long)]
    hcloud_token: bool,
}

/// the API token of the Hetzner Cloud project of the command. No Debug, the token never shows
pub struct HetznerToken(String);

#[derive(Args, Debug, PartialEq, Eq)]
pub struct DeploymentArgs {
    /// The name of the deployment
    #[arg(env = NAME_VARIABLE)]
    name: String,
    /// Ask for the password, even when SAK_PASSWORD is set
    ///
    /// The password has at least 16 characters, the keys of the deployment are derived from it.
    /// It is never an argument: it comes from SAK_PASSWORD or is typed without echo.
    #[arg(long)]
    password: bool,
    /// The location of the server, its data volume and its addresses
    #[arg(long, env = LOCATION_VARIABLE, default_value = "nbg1", value_parser = parse_location)]
    location: Location,
    /// The DNS mode: domain or subdomain
    ///
    /// domain manages <root domain> and *.<root domain>, subdomain manages <name>.<root domain>
    /// and *.<name>.<root domain>. Default: subdomain for a create, the deployment's own for a
    /// deploy.
    #[arg(long, value_parser = parse_dns_mode)]
    dns_mode: Option<DnsMode>,
    /// The DNS zone of the deployment, e.g. pelle.io
    ///
    /// Needed for a create, a deploy takes the deployment's own. The zone must exist in the
    /// project, SAK does not create it.
    #[arg(long)]
    root_domain: Option<String>,
    /// Who signs in: single or multi
    ///
    /// single: one user, the admin, signs in with the password of the deployment. multi: users
    /// sign in by e-mail, the admin also with the password; needs --admin-email and --smtp-host.
    #[arg(long, env = USER_MODE_VARIABLE, value_parser = parse_user_mode)]
    user_mode: Option<UserMode>,
    /// The e-mail address of the admin (multi user)
    #[arg(long, env = ADMIN_EMAIL_VARIABLE)]
    admin_email: Option<String>,
    /// The mail server for the login links (multi user)
    #[arg(long, env = SMTP_HOST_VARIABLE)]
    smtp_host: Option<String>,
    /// The port of the mail server
    #[arg(long, env = SMTP_PORT_VARIABLE, default_value_t = DEFAULT_SMTP_PORT)]
    smtp_port: u16,
    /// The user name at the mail server
    #[arg(long, env = SMTP_USERNAME_VARIABLE, default_value = "")]
    smtp_username: String,
    /// The CA of the certificates: production or staging
    ///
    /// staging is the staging CA of Let's Encrypt for test deployments, sakctl then trusts its
    /// roots. Default: production for a create, the deployment's own for a deploy.
    #[arg(long, value_parser = parse_acme_ca)]
    acme_ca: Option<AcmeCa>,
    /// Ask for the password at the mail server, even when SAK_SMTP_PASSWORD is set
    ///
    /// It is never an argument: it comes from SAK_SMTP_PASSWORD or is typed without echo. Without
    /// either, the mail server has none, and a deploy takes over the running one.
    #[arg(long)]
    smtp_password: bool,
    /// Use STARTTLS with the mail server: true or false
    #[arg(long,
        action = clap::ArgAction::Set, env = SMTP_STARTTLS_VARIABLE, default_value_t = DEFAULT_SMTP_STARTTLS, value_parser = parse_flag)]
    smtp_starttls: bool,
    /// Show the output of ansible while it provisions the server
    #[arg(long)]
    show_log: bool,
}

fn parse_location(value: &str) -> Result<Location, String> {
    LOCATIONS
        .iter()
        .find(|location| key(&location.id) == value)
        .map(|location| location.id)
        .ok_or_else(|| {
            let known: Vec<String> = LOCATIONS.iter().map(|location| key(&location.id)).collect();
            format!("one of {}", known.join(", "))
        })
}

fn parse_acme_ca(value: &str) -> Result<AcmeCa, String> {
    AcmeCa::parse(value).ok_or_else(|| {
        let known: Vec<&str> = AcmeCa::ALL.iter().map(|ca| ca.as_str()).collect();
        format!("one of {}", known.join(", "))
    })
}

fn parse_user_mode(value: &str) -> Result<UserMode, String> {
    UserMode::parse(value).ok_or_else(|| {
        let known: Vec<&str> = UserMode::ALL.iter().map(|mode| mode.as_str()).collect();
        format!("one of {}", known.join(", "))
    })
}

fn parse_dns_mode(value: &str) -> Result<DnsMode, String> {
    DnsMode::parse(value).ok_or_else(|| {
        let known: Vec<&str> = DnsMode::ALL.iter().map(|mode| mode.as_str()).collect();
        format!("one of {}", known.join(", "))
    })
}

/// whether the arguments ask for the command line: a command, help or the version as the first
/// argument. Anything else (nothing, or what a desktop passes) opens the window
pub fn is_command_line(args: &[String]) -> bool {
    matches!(
        args.first().map(String::as_str),
        Some(
            "list"
                | "create"
                | "deploy"
                | "destroy"
                | "config"
                | "ssh"
                | "backup"
                | "restore"
                | "maintenance"
                | "update"
                | "--help"
                | "-h"
                | "--version"
                | "-V"
        )
    )
}

/// runs the command and gives the exit code of the process
pub fn run(command: Command) -> i32 {
    // ctrl+c stops a provisioning and removes the files of its run
    crate::core::provision::stop_runs_on_signal();
    // an update needs neither a token nor the checks
    let result = match command {
        Command::Update { check } => tauri::async_runtime::block_on(update(check)),
        command => tauri::async_runtime::block_on(execute(command)).map(|()| EXIT_OK),
    };
    match result {
        Ok(code) => code,
        Err(error) => {
            log(Level::Error, &error.message);
            error.exit_code
        }
    }
}

impl HetznerToken {
    fn tokens(&self) -> Vec<(ProviderType, String)> {
        vec![(ProviderType::Hetzner, self.0.clone())]
    }
}

/// the token of the project: HCLOUD_TOKEN unless `ask`, else typed on the terminal without echo.
/// Never an argument, because every process of the machine can read the arguments. Without a token
/// and a terminal, a usage error
fn hcloud_token(ask: bool, from_env: Option<String>) -> Result<HetznerToken, WizardError> {
    // a token copied with a line break or a space around it is the token
    if !ask && let Some(token) = from_env.filter(|token| !token.trim().is_empty()) {
        return Ok(HetznerToken(token.trim().to_string()));
    }
    if !std::io::stdin().is_terminal() {
        return Err(WizardError::new(t!("cli.no_hcloud_token"), EXIT_USAGE));
    }
    rpassword::prompt_password(t!("cli.hcloud_token_prompt").as_ref())
        .map(|token| HetznerToken(token.trim().to_string()))
        .map_err(|error| error.to_string().into())
}

/// the password of the deployment and its repetition: SAK_PASSWORD unless `ask`, else typed on the
/// terminal without echo, two times for a create. Never an argument, because every process of the
/// machine can read the arguments. Without SAK_PASSWORD and a terminal, a usage error
fn password(
    ask: bool,
    twice: bool,
    from_env: Option<String>,
) -> Result<(String, String), WizardError> {
    if !ask && let Some(password) = from_env.filter(|password| !password.is_empty()) {
        return Ok((password.clone(), password));
    }
    if !std::io::stdin().is_terminal() {
        return Err(WizardError::new(t!("cli.no_password"), EXIT_USAGE));
    }
    let typed = |prompt: std::borrow::Cow<'_, str>| {
        rpassword::prompt_password(prompt.as_ref()).map_err(|error| error.to_string())
    };
    let first = typed(t!("cli.password_prompt"))?;
    let second = if twice {
        typed(t!("cli.password_repeat_prompt"))?
    } else {
        first.clone()
    };
    Ok((first, second))
}

/// the password at the mail server: SAK_SMTP_PASSWORD unless `ask`, else typed on the terminal
/// without echo; empty when the variable is not set. Never an argument, because every process of
/// the machine can read the arguments
fn smtp_password(ask: bool, from_env: Option<String>) -> Result<String, WizardError> {
    if !ask {
        return Ok(from_env.unwrap_or_default());
    }
    if !std::io::stdin().is_terminal() {
        return Err(WizardError::new(t!("cli.no_smtp_password"), EXIT_USAGE));
    }
    rpassword::prompt_password(t!("cli.smtp_password_prompt").as_ref())
        .map_err(|error| error.to_string().into())
}

/// SAK_SMTP_PASSWORD of the environment of the process
fn smtp_password_variable() -> Option<String> {
    std::env::var(SMTP_PASSWORD_VARIABLE).ok()
}

/// a resource as the command line names it: its kind in the language of the command line and its
/// name, `ssh key 'sak-alpha'`, `SSH-Key 'sak-alpha'`
fn title(kind: &str, name: &str) -> String {
    format!(
        "{} '{name}'",
        t!(format!("shared.deployments.resource.{kind}"))
    )
}

/// SAK_PASSWORD of the environment of the process
fn password_variable() -> Option<String> {
    std::env::var(PASSWORD_VARIABLE).ok()
}

impl DeploymentArgs {
    fn deployment(
        &self,
        intent: Intent,
        delete_data: bool,
        (password, password_repeat): (String, String),
        smtp_password: &str,
    ) -> NewDeployment {
        NewDeployment {
            name: self.name.clone(),
            password,
            password_repeat,
            location: self.location,
            dns_mode: self.dns_mode,
            root_domain: self.root_domain.clone(),
            intent,
            delete_data,
            user_mode: self.user_mode,
            acme_ca: self.acme_ca,
            smtp: self.smtp_host.as_ref().map(|host| Smtp {
                host: host.clone(),
                port: self.smtp_port,
                username: self.smtp_username.clone(),
                password: smtp_password.to_string(),
                starttls: self.smtp_starttls,
            }),
            admin_email: self.admin_email.clone(),
            // the command line always runs the playbook of a deploy
            force: true,
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
    let found = tokio::task::spawn_blocking(move || {
        // the window shows the progress of a pull ten times a second; the log shows each tenth
        let mut logged = 0;
        checks::run(checks::checks(tokens), &mut |event| {
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
                CheckEvent::Failed { .. }
                | CheckEvent::Listed { .. }
                | CheckEvent::Finished { .. } => {}
            }
            Ok(())
        })
    })
    .await
    .map_err(checks::not_finished)??;
    let runtime = ContainerRuntime::default();
    if let Some(found) = found.runtime {
        runtime.set(found);
    }
    Ok(runtime)
}

async fn execute(command: Command) -> Result<(), WizardError> {
    let manager = DeploymentManager;
    // asked before the checks, because they take some time
    let password = match &command {
        Command::List(_) | Command::Update { .. } => None,
        Command::Create { deployment, .. } => {
            Some(password(deployment.password, true, password_variable())?)
        }
        Command::Deploy { deployment, .. } | Command::Destroy { deployment, .. } => {
            Some(password(deployment.password, false, password_variable())?)
        }
        Command::Maintenance(maintenance) => Some(password(
            maintenance.args().password,
            false,
            password_variable(),
        )?),
        Command::Config(ConfigCommand::Show { password: ask, .. })
        | Command::Ssh { password: ask, .. }
        | Command::Backup { password: ask, .. }
        | Command::Restore { password: ask, .. } => {
            Some(password(*ask, false, password_variable())?)
        }
    };
    let password = password.unwrap_or_default();
    let smtp_password = match &command {
        Command::Create { deployment, .. } | Command::Deploy { deployment, .. } => {
            smtp_password(deployment.smtp_password, smtp_password_variable())?
        }
        _ => String::new(),
    };
    let provider = match &command {
        Command::List(provider)
        | Command::Create { provider, .. }
        | Command::Deploy { provider, .. }
        | Command::Destroy { provider, .. }
        | Command::Config(ConfigCommand::Show { provider, .. })
        | Command::Ssh { provider, .. }
        | Command::Backup { provider, .. }
        | Command::Restore { provider, .. } => provider,
        Command::Maintenance(maintenance) => &maintenance.args().provider,
        Command::Update { .. } => unreachable!("run() updates without the checks"),
    };
    let token = hcloud_token(provider.hcloud_token, std::env::var("HCLOUD_TOKEN").ok())?;
    let runtime = check(&token).await?;
    match command {
        Command::Update { .. } => unreachable!("run() updates without the checks"),
        Command::List(_) => list(&manager, &token).await,
        Command::Maintenance(command) => {
            maintenance(&manager, &token, &runtime, &command, &password.0).await
        }
        Command::Config(ConfigCommand::Show { name, .. }) => {
            show_config(&manager, &token, &name, &password.0).await
        }
        Command::Ssh { name, command, .. } => {
            ssh(&manager, &token, &runtime, &name, &password.0, &command).await
        }
        Command::Backup { name, .. } => {
            backup(&manager, &token, &runtime, &name, &password.0).await
        }
        Command::Restore {
            name, file, yes, ..
        } => restore(&manager, &token, &runtime, &name, &password.0, &file, yes).await,
        Command::Create { deployment, .. } => {
            deploy(
                &manager,
                &token,
                &deployment.deployment(Intent::Create, false, password, &smtp_password),
                &runtime,
                deployment.show_log,
            )
            .await
        }
        Command::Deploy { deployment, .. } => {
            let mut new = deployment.deployment(Intent::Deploy, false, password, &smtp_password);
            // what is not given comes from the deployment: its user mode and, for multi user,
            // the admin and the mail server, detected as the window does
            let incomplete = new.user_mode != Some(UserMode::Single)
                && (new.user_mode.is_none() || new.smtp.is_none() || new.admin_email.is_none());
            if incomplete
                && let Ok(detected) = manager
                    .detected(token.tokens(), &new.name, &new.password)
                    .await
            {
                new = complete(new, &detected, &smtp_password)?;
            }
            deploy(&manager, &token, &new, &runtime, deployment.show_log).await
        }
        Command::Destroy {
            deployment,
            delete_data,
            ..
        } => {
            deploy(
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

async fn list(manager: &DeploymentManager, provider: &HetznerToken) -> Result<(), WizardError> {
    let deployments = manager.list(provider.tokens()).await?;
    if deployments.is_empty() {
        log(Level::Info, t!("shared.deployments.empty"));
    }
    for deployment in deployments {
        let location = deployment
            .location
            .map(|location| location.to_string())
            .unwrap_or_default();
        log(
            Level::Info,
            format!(
                "{} {} {} {} {}",
                deployment.name,
                t!(format!(
                    "shared.deployments.status.{}",
                    key(&deployment.status)
                )),
                location,
                deployment.provider.name,
                deployment.frontend_url.as_deref().unwrap_or_default()
            )
            .trim_end(),
        );
        for resource in deployment.resources {
            let resource_name = title(&key(&resource.kind), &resource.name);
            // its status, then its detail
            let status = t!(format!(
                "shared.resource_status.{}",
                resource_status(resource.status)
            ));
            let detail = resource
                .detail
                .filter(|_| resource_status(resource.status) != "missing")
                .map(|detail| format!(", {detail}"))
                .unwrap_or_default();
            log(Level::Info, format!("{resource_name} {status}{detail}"));
            for change in resource.changes {
                log(Level::Warn, format!("{resource_name} {change}"));
            }
        }
    }
    Ok(())
}

/// prints the running configuration of the deployment, one line for each setting
/// a session on the server, or its command; the exit code of ssh is the exit code of sakctl
async fn ssh(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    runtime: &ContainerRuntime,
    name: &str,
    password: &str,
    command: &[String],
) -> Result<(), WizardError> {
    let target = manager
        .ssh_target(provider.tokens(), name, password)
        .await?;
    let runtime = runtime
        .get()
        .ok_or_else(|| t!("shared.provision.no_runtime").to_string())?;
    log(
        Level::Info,
        t!(
            "cli.ssh_connecting",
            name = name.trim(),
            host = target.host.as_str()
        ),
    );
    let command = command.to_vec();
    let code = tokio::task::spawn_blocking(move || {
        crate::core::ssh::open_session(runtime, &target, &command)
    })
    .await
    .map_err(|error| error.to_string())??;
    match code {
        0 => Ok(()),
        code => Err(WizardError::new(
            t!("cli.ssh_ended", code = code).to_string(),
            code,
        )),
    }
}

/// the maintenance of the server: each line of the server logged, the answer as lines or, with
/// --json, as it is. A reboot waits until the deployment answers again, unless --no-wait
async fn maintenance(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    runtime: &ContainerRuntime,
    command: &MaintenanceCommand,
    password: &str,
) -> Result<(), WizardError> {
    let args = command.args();
    let name = args.name.trim().to_string();
    let target = manager
        .ssh_target(provider.tokens(), &name, password)
        .await?;
    let runtime = runtime
        .get()
        .ok_or_else(|| t!("shared.provision.no_runtime").to_string())?;
    let json = args.json;
    if !json {
        log(
            Level::Info,
            t!(
                "cli.maintenance.connecting",
                name = name,
                host = target.host.as_str()
            ),
        );
    }
    let mut on_line = move |line: String| {
        if !json {
            log(Level::Info, line)
        }
    };
    let lines = match command {
        MaintenanceCommand::Status(_) => {
            let status = tokio::task::spawn_blocking(move || {
                crate::core::maintenance::status(runtime, &target, &mut on_line)
            })
            .await
            .map_err(|error| error.to_string())??;
            if json {
                return print_json(&status);
            }
            status_lines(&name, &status)
        }
        MaintenanceCommand::Update(_) => {
            let update = tokio::task::spawn_blocking(move || {
                crate::core::maintenance::update(runtime, &target, &mut on_line)
            })
            .await
            .map_err(|error| error.to_string())??;
            if json {
                return print_json(&update);
            }
            update_lines(&name, &update)
        }
        MaintenanceCommand::Reboot {
            if_pending,
            no_wait,
            ..
        } => {
            let if_pending = *if_pending;
            let reboot = tokio::task::spawn_blocking(move || {
                crate::core::maintenance::reboot(runtime, &target, if_pending, &mut on_line)
            })
            .await
            .map_err(|error| error.to_string())??;
            if json {
                print_json(&reboot)?;
            } else if reboot.rebooting {
                log(
                    Level::Info,
                    t!(
                        "cli.maintenance.rebooting",
                        name = name,
                        at = reboot.at.as_deref().unwrap_or_default()
                    ),
                );
            } else {
                log(
                    Level::Info,
                    t!("cli.maintenance.reboot_not_pending", name = name),
                );
            }
            if reboot.rebooting && !*no_wait {
                wait_after_reboot(manager, provider, &name, password, json).await?;
            }
            return Ok(());
        }
    };
    for (level, line) in lines {
        log(level, line);
    }
    Ok(())
}

fn print_json(value: &impl serde::Serialize) -> Result<(), WizardError> {
    let text = serde_json::to_string_pretty(value).map_err(|error| error.to_string())?;
    anstream::println!("{text}");
    Ok(())
}

/// waits until the deployment answers at its public endpoint again, after the server went down
async fn wait_after_reboot(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    name: &str,
    password: &str,
    json: bool,
) -> Result<(), WizardError> {
    let (api, trust, address) = manager
        .public_endpoint(provider.tokens(), name, password)
        .await?;
    // the server goes down some seconds after the answer; before that it answers no request,
    // the ingress stopped first
    tokio::time::sleep(std::time::Duration::from_secs(10)).await;
    if !json {
        log(
            Level::Info,
            t!("shared.deployments.waiting_for_api", url = api.as_str()),
        );
    }
    crate::core::config::wait_for_api(
        &api,
        address,
        trust,
        crate::core::config::API_WAIT,
        std::time::Duration::from_secs(5),
    )
    .await?;
    if !json {
        log(Level::Info, t!("cli.maintenance.back", name = name));
    }
    Ok(())
}

/// what `maintenance status` prints: whether a reboot is pending and why, the services on old
/// libraries, the upgrades since the boot
fn status_lines(
    name: &str,
    status: &crate::core::maintenance::RebootStatus,
) -> Vec<(Level, String)> {
    let mut lines = Vec::new();
    if status.reboot_pending {
        let reasons: Vec<String> = status
            .reasons
            .iter()
            .map(|reason| match reason.as_str() {
                "kernel" => t!(
                    "cli.maintenance.reason_kernel",
                    running = status.kernel.running.as_str(),
                    installed = status.kernel.installed.as_str()
                )
                .to_string(),
                "reboot-required" => t!("cli.maintenance.reason_reboot_required").to_string(),
                other => other.to_string(),
            })
            .collect();
        lines.push((
            Level::Warn,
            t!(
                "cli.maintenance.pending",
                name = name,
                reasons = reasons.join(", ")
            )
            .to_string(),
        ));
    } else {
        lines.push((
            Level::Info,
            t!("cli.maintenance.not_pending", name = name).to_string(),
        ));
    }
    if !status.services.is_empty() {
        lines.push((
            Level::Info,
            t!(
                "cli.maintenance.services",
                services = status.services.join(", ")
            )
            .to_string(),
        ));
    }
    if !status.updates_since_boot.is_empty() {
        lines.push((
            Level::Info,
            t!(
                "cli.maintenance.since_boot",
                time = status.booted_at.as_str()
            )
            .to_string(),
        ));
        lines.extend(
            status
                .updates_since_boot
                .iter()
                .map(|change| (Level::Info, change_line(change))),
        );
    }
    lines
}

/// what `maintenance update` prints: each package that changed, how many, and a pending reboot
fn update_lines(
    name: &str,
    update: &crate::core::maintenance::UpdateResult,
) -> Vec<(Level, String)> {
    let mut lines: Vec<(Level, String)> = update
        .upgraded
        .iter()
        .chain(&update.installed)
        .map(|change| (Level::Info, change_line(change)))
        .collect();
    lines.push((
        Level::Info,
        if lines.is_empty() {
            t!("cli.maintenance.up_to_date", name = name).to_string()
        } else {
            t!(
                "cli.maintenance.updated",
                upgraded = update.upgraded.len(),
                installed = update.installed.len()
            )
            .to_string()
        },
    ));
    if update.reboot_pending {
        lines.push((
            Level::Warn,
            t!("cli.maintenance.reboot_hint", name = name).to_string(),
        ));
    }
    lines
}

/// `openssl 3.5.1-1 → 3.5.4-1`, or `linux-image-6.12.41 6.12.41-1 (new)`
fn change_line(change: &crate::core::maintenance::PackageChange) -> String {
    match &change.from {
        Some(from) => format!("{} {from} → {}", change.package, change.to),
        None => t!(
            "cli.maintenance.installed_new",
            package = change.package.as_str(),
            to = change.to.as_str()
        )
        .to_string(),
    }
}

/// a backup of the deployment to `~/.sak/backups/<name>/`, each step of the server logged
async fn backup(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    runtime: &ContainerRuntime,
    name: &str,
    password: &str,
) -> Result<(), WizardError> {
    let name = name.trim().to_string();
    let target = manager
        .ssh_target(provider.tokens(), &name, password)
        .await?;
    let key = crate::core::keys::backup_key(&name, password)?;
    let runtime = runtime
        .get()
        .ok_or_else(|| t!("shared.provision.no_runtime").to_string())?;
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
        BackupManager.backup(runtime, &target, &key, &path, &mut |line| {
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
async fn restore(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    runtime: &ContainerRuntime,
    name: &str,
    password: &str,
    file: &std::path::Path,
    yes: bool,
) -> Result<(), WizardError> {
    let name = name.trim().to_string();
    let target = manager
        .ssh_target(provider.tokens(), &name, password)
        .await?;
    let key = crate::core::keys::backup_key(&name, password)?;
    let runtime = runtime
        .get()
        .ok_or_else(|| t!("shared.provision.no_runtime").to_string())?;
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
        BackupManager.restore(runtime, &target, &key, &path, &mut |line| {
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

/// the name of the deployment typed on the terminal; anything else changes nothing
fn confirm_restore(name: &str, file: &std::path::Path) -> Result<(), WizardError> {
    use std::io::Write;
    if !std::io::stdin().is_terminal() {
        return Err(WizardError::new(
            t!("cli.restore_no_terminal", name = name),
            EXIT_USAGE,
        ));
    }
    eprint!(
        "{}",
        t!("cli.restore_confirm", name = name, path = file.display())
    );
    std::io::stderr()
        .flush()
        .map_err(|error| error.to_string())?;
    let mut typed = String::new();
    std::io::stdin()
        .read_line(&mut typed)
        .map_err(|error| error.to_string())?;
    if typed.trim() == name {
        Ok(())
    } else {
        Err(t!("cli.restore_not_confirmed").to_string().into())
    }
}

/// a size in bytes for people: `812 B`, `3.4 MiB`
fn size(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KiB", "MiB", "GiB", "TiB"];
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit < UNITS.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{bytes} B")
    } else {
        format!("{value:.1} {}", UNITS[unit])
    }
}

async fn show_config(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    name: &str,
    password: &str,
) -> Result<(), WizardError> {
    let (url, config) = manager
        .running_config(provider.tokens(), name, password)
        .await?;
    for line in shown_lines(name, &url, &config) {
        log(Level::Info, line);
    }
    Ok(())
}

/// what `config show` prints: where it asked, then the settings; no secret
fn shown_lines(name: &str, url: &str, config: &crate::core::config::RunningConfig) -> Vec<String> {
    std::iter::once(t!("shared.config.shown", name = name.trim(), url = url).to_string())
        .chain(config_lines(config))
        .collect()
}

/// `smtp.host smtp.example.org`, … : the settings as `<key> <value>`; the password of the mail
/// server only as `set` or `-`
fn config_lines(config: &crate::core::config::RunningConfig) -> Vec<String> {
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

/// plans, prints the plan and applies it, as the plan page of the window does. Prints each
/// resource when it is done; the output of ansible only with `show_log`
async fn deploy(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    deployment: &NewDeployment,
    runtime: &ContainerRuntime,
    show_log: bool,
) -> Result<(), WizardError> {
    // the project is read once, for the plan and the apply
    let prepared = manager.prepare(provider.tokens(), deployment).await?;
    let plan = manager
        .plan_prepared(&prepared, deployment, runtime)
        .await?;
    let intro = match deployment.intent {
        Intent::Create => t!("shared.plan.intro"),
        Intent::Deploy => t!("shared.plan.intro_deploy"),
        Intent::Destroy => t!("shared.plan.intro_destroy"),
    };
    log(Level::Info, format!("{intro} {}", plan.name));
    // each resource by its kind and name in the language of the command line
    let titles: HashMap<String, String> = plan
        .resources
        .iter()
        .map(|resource| {
            (
                resource.resource.clone(),
                title(&resource.kind, &resource.name),
            )
        })
        .collect();
    let titled = |resource: &str| {
        titles
            .get(resource)
            .cloned()
            .unwrap_or_else(|| resource.to_string())
    };
    for resource in &plan.resources {
        // its status, then what the plan does to it
        let status = t!(format!(
            "shared.resource_status.{}",
            resource.resource_status
        ));
        let action = resource
            .action
            .map(|action| format!(", {}", t!(format!("shared.plan.{action}"))))
            .unwrap_or_default();
        // a resource that the apply removes is a warning
        let level = if resource.action == Some("will_destroy") {
            Level::Warn
        } else {
            Level::Info
        };
        let title = titled(&resource.resource);
        log(level, format!("{title} {status}{action}"));
        for change in &resource.changes {
            log(Level::Warn, format!("{title} {change}"));
        }
    }

    // what the apply does to a resource when it starts, from what the plan found
    let working: HashMap<String, &str> = plan
        .resources
        .iter()
        .map(|resource| (resource.resource.clone(), resource.working))
        .collect();
    // a resource, then what is done to it, with the texts of the window: `Server 'alpha': created`
    let mut report = |event: CreateEvent| match event {
        CreateEvent::Started { resource } => {
            let key = working.get(&resource).copied().unwrap_or("working");
            log(
                Level::Info,
                format!(
                    "{}: {}",
                    titled(&resource),
                    t!(format!("shared.working.{key}"))
                ),
            )
        }
        CreateEvent::Applied { resource, applied } => log(
            Level::Info,
            format!("{}: {}", titled(&resource), t!(applied_key(applied))),
        ),
        CreateEvent::Destroyed { resource, gone } => log(
            Level::Info,
            format!("{}: {}", titled(&resource), t!(destroyed_key(gone))),
        ),
        CreateEvent::Provisioning { message } => log(Level::Info, message),
        // the log keeps the text of a line of ansible without its spaces and empty lines
        CreateEvent::Output { line } => {
            if show_log && !line.trim().is_empty() {
                log(Level::Info, format!("[ansible] {}", line.trim()))
            }
        }
        // one line for each task of the playbook when it ends; a failure as an error
        CreateEvent::TaskDone {
            task,
            outcome,
            message,
            ..
        } => {
            let level = match outcome {
                TaskOutcome::Failed | TaskOutcome::Unreachable => Level::Error,
                TaskOutcome::Ignored => Level::Warn,
                _ => Level::Info,
            };
            log(
                level,
                t!(
                    format!("cli.task.{}", key(&outcome)),
                    task = task,
                    message = message.unwrap_or_default()
                ),
            )
        }
        // the error of the run tells what failed, the command ends with it
        CreateEvent::TaskStarted { .. } | CreateEvent::ProvisionFailed { .. } => {}
    };
    manager
        .apply(prepared, deployment, runtime, &mut report)
        .await?;
    let done = match deployment.intent {
        Intent::Destroy if deployment.delete_data => {
            t!("cli.destroyed_with_data", name = deployment.name)
        }
        Intent::Destroy => t!("cli.destroyed", name = deployment.name),
        _ => t!("cli.done", name = deployment.name),
    };
    log(Level::Info, done);
    Ok(())
}

/// the level of a line of the log, with its colour on a terminal
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Level {
    Info,
    /// a change the plan found, a resource the apply removes, an ignored failure of a task
    Warn,
    /// the command failed
    Error,
}

impl Level {
    fn name(self) -> &'static str {
        match self {
            Level::Info => "INFO",
            Level::Warn => "WARN",
            Level::Error => "ERROR",
        }
    }

    fn style(self) -> Style {
        let color = match self {
            Level::Info => AnsiColor::Green,
            Level::Warn => AnsiColor::Yellow,
            Level::Error => AnsiColor::Red,
        };
        Style::new().fg_color(Some(color.into())).bold()
    }
}

/// `<date> <level> <message>`, one space between each
fn log_line(date: &str, level: Level, message: impl Display) -> String {
    let style = level.style();
    format!("{date} {style}{}{style:#} {message}", level.name())
}

/// prints a line of the log, an error to stderr. anstream removes the colour when the output is
/// not a terminal or NO_COLOR is set
fn log(level: Level, message: impl Display) {
    let date = chrono::Local::now().format("%Y-%m-%d %H:%M:%S").to_string();
    let line = log_line(&date, level, message);
    match level {
        Level::Error => anstream::eprintln!("{line}"),
        _ => anstream::println!("{line}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::error::{
        EXIT_DOMAIN_TAKEN, EXIT_FAILED, EXIT_MIXED_DNS_MODES, EXIT_NOT_OWNED,
        EXIT_RECREATE_REFUSED, EXIT_UPDATE_AVAILABLE, EXIT_USAGE,
    };

    fn parse(words: &[&str]) -> Result<Cli, clap::Error> {
        Cli::try_parse_from(std::iter::once("sakctl").chain(words.iter().copied()))
    }

    #[test]
    fn a_log_line_is_date_level_message_with_the_level_coloured() {
        let line = log_line(
            "2026-10-28 14:03:12",
            Level::Info,
            "Server 'alpha': created",
        );
        assert_eq!(
            anstream::adapter::strip_str(&line).to_string(),
            "2026-10-28 14:03:12 INFO Server 'alpha': created"
        );
        assert!(line.contains("\x1b[1m\x1b[32mINFO\x1b[0m Server"));
        let error = log_line("2026-10-28 14:03:12", Level::Error, "failed");
        assert_eq!(
            anstream::adapter::strip_str(&error).to_string(),
            "2026-10-28 14:03:12 ERROR failed"
        );
        assert!(error.contains("\x1b[31m"));
    }

    #[test]
    fn the_help_lists_the_exit_codes_of_the_errors() {
        for code in [
            EXIT_OK,
            EXIT_FAILED,
            EXIT_USAGE,
            EXIT_DOMAIN_TAKEN,
            EXIT_MIXED_DNS_MODES,
            EXIT_NOT_OWNED,
            EXIT_RECREATE_REFUSED,
            EXIT_UPDATE_AVAILABLE,
        ] {
            assert!(EXIT_CODES.contains(&format!("\n  {code}  ")), "{code}");
        }
        for command in [
            &["--help"][..],
            &["create", "--help"],
            &["destroy", "--help"],
        ] {
            let help = parse(command).unwrap_err().to_string();
            assert!(help.contains("Exit codes:"), "{help}");
        }
        // clap ends a wrong argument with the code the help names
        assert_eq!(
            parse(&["create", "--moon"]).unwrap_err().exit_code(),
            EXIT_USAGE
        );
    }

    #[test]
    fn maintenance_takes_the_name_and_its_options() {
        let cli = parse(&["maintenance", "reboot", "alpha", "--if-pending", "--json"]).unwrap();
        let Some(Command::Maintenance(MaintenanceCommand::Reboot {
            args,
            if_pending,
            no_wait,
        })) = cli.command
        else {
            panic!("maintenance reboot");
        };
        assert_eq!(
            (args.name.as_str(), args.json, if_pending, no_wait),
            ("alpha", true, true, false)
        );
        assert!(matches!(
            parse(&["maintenance", "status", "alpha"]).unwrap().command,
            Some(Command::Maintenance(MaintenanceCommand::Status(_)))
        ));
        assert!(parse(&["maintenance", "update", "alpha", "--if-pending"]).is_err());
        assert!(is_command_line(&["maintenance".to_string()]));
    }

    #[test]
    fn maintenance_prints_the_reasons_and_the_changes_of_packages() {
        use crate::core::maintenance::{Kernel, PackageChange, RebootStatus, UpdateResult};
        let change = |package: &str, from: Option<&str>, to: &str| PackageChange {
            package: package.to_string(),
            from: from.map(String::from),
            to: to.to_string(),
            time: None,
        };
        let status = RebootStatus {
            reboot_pending: true,
            reasons: vec!["kernel".to_string()],
            kernel: Kernel {
                running: "6.12.38".to_string(),
                installed: "6.12.41".to_string(),
            },
            services: vec!["dbus.service".to_string()],
            updates_since_boot: vec![change("openssl", Some("3.5.1-1"), "3.5.4-1")],
            booted_at: "2026-10-21T04:00:12Z".to_string(),
        };
        let lines: Vec<String> = status_lines("alpha", &status)
            .into_iter()
            .map(|(_, line)| line)
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
            reasons: vec!["kernel".to_string()],
        };
        let lines: Vec<String> = update_lines("alpha", &update)
            .into_iter()
            .map(|(_, line)| line)
            .collect();
        assert_eq!(
            lines,
            [
                "openssl 3.5.1-1 → 3.5.4-1",
                "linux-image-6.12.41 6.12.41-1 (new)",
                "1 packages upgraded, 1 installed.",
                "A reboot is pending, run sakctl maintenance reboot alpha."
            ]
        );
        let nothing = UpdateResult {
            upgraded: vec![],
            installed: vec![],
            reboot_pending: false,
            reasons: vec![],
        };
        assert_eq!(
            update_lines("alpha", &nothing)[0].1,
            "All packages of alpha are up-to-date."
        );
    }

    #[test]
    fn config_show_takes_the_name_and_password_and_prints_no_secret() {
        let cli = parse(&["config", "show", "alpha", "--hcloud-token", "--password"]).unwrap();
        let Some(Command::Config(ConfigCommand::Show { name, password, .. })) = cli.command else {
            panic!("config show");
        };
        // --password asks for it, it takes no value
        assert_eq!((name.as_str(), password), ("alpha", true));
        assert!(
            parse(&[
                "config",
                "show",
                "alpha",
                "--hcloud-token",
                "--password",
                "p"
            ])
            .is_err()
        );
        assert!(is_command_line(&["config".to_string()]));

        let config = crate::core::config::RunningConfig {
            smtp: Some(crate::core::config::RunningSmtp {
                host: "smtp.example.org".to_string(),
                port: 465,
                username: None,
                password_set: true,
                password: Some("the-smtp-password".to_string()),
                from: "noreply@example.org".to_string(),
                tls: true,
                starttls: false,
            }),
            admins: vec!["admin@example.org".to_string()],
        };
        let lines = config_lines(&config);
        assert!(lines.contains(&"smtp.host smtp.example.org".to_string()));
        assert!(lines.contains(&"smtp.username -".to_string()));
        assert!(lines.contains(&"smtp.password set".to_string()));
        assert_eq!(
            config_lines(&crate::core::config::RunningConfig {
                smtp: None,
                admins: vec![]
            }),
            vec![t!("shared.config.no_smtp").to_string()]
        );

        // the output has no password and no part of the api key
        let password = "a password of sixteen";
        let key = crate::core::keys::api_key("alpha", password).unwrap();
        let shown = shown_lines(" alpha ", "https://api.pelle.io", &config).join("\n");
        assert!(shown.contains("https://api.pelle.io"), "{shown}");
        assert!(!shown.contains(password) && !shown.contains(&key.public_key()));
        // nor the password of the mail server, which the backend answers for a redeploy
        assert!(!shown.contains("the-smtp-password"));
    }

    #[test]
    fn ssh_takes_the_name_and_a_command_after_two_dashes() {
        assert!(is_command_line(&["ssh".to_string()]));
        let Some(Command::Ssh {
            name,
            command,
            password,
            ..
        }) = parse(&["ssh", "alpha", "--", "uptime", "-p"])
            .unwrap()
            .command
        else {
            panic!("no ssh command");
        };
        assert_eq!(name, "alpha");
        assert_eq!(command, ["uptime", "-p"]);
        assert!(!password);
        let Some(Command::Ssh { command, .. }) = parse(&["ssh", "alpha"]).unwrap().command else {
            panic!("no ssh command");
        };
        assert!(command.is_empty());
    }

    #[test]
    fn backup_takes_the_name_and_restore_the_name_and_the_file() {
        assert!(is_command_line(&["backup".to_string()]));
        assert!(is_command_line(&["restore".to_string()]));
        let Some(Command::Backup { name, password, .. }) =
            parse(&["backup", "alpha"]).unwrap().command
        else {
            panic!("no backup command");
        };
        assert_eq!((name.as_str(), password), ("alpha", false));
        let Some(Command::Restore {
            name, file, yes, ..
        }) = parse(&[
            "restore",
            "alpha",
            "alpha-20261029T080000Z.age.sak",
            "--yes",
        ])
        .unwrap()
        .command
        else {
            panic!("no restore command");
        };
        assert_eq!(name, "alpha");
        assert_eq!(file, std::path::Path::new("alpha-20261029T080000Z.age.sak"));
        assert!(yes);
        // a restore needs its file
        assert!(parse(&["restore", "alpha"]).is_err());
    }

    #[test]
    fn a_size_is_shown_in_the_largest_unit_below_1024() {
        assert_eq!(size(812), "812 B");
        assert_eq!(size(3 * 1024 * 1024 + 400 * 1024), "3.4 MiB");
        assert_eq!(size(2048), "2.0 KiB");
    }

    #[test]
    fn the_hcloud_token_comes_from_hcloud_token_or_is_typed_never_from_an_argument() {
        assert_eq!(
            hcloud_token(false, Some("token".into())).unwrap().0,
            "token"
        );
        // the api gets the token without what was copied around it
        assert_eq!(
            hcloud_token(false, Some(" token\n".into())).unwrap().0,
            "token"
        );
        if !std::io::stdin().is_terminal() {
            for (ask, from_env) in [
                (true, Some("token".to_string())),
                (false, None),
                (false, Some(" ".to_string())),
            ] {
                assert_eq!(
                    hcloud_token(ask, from_env)
                        .err()
                        .map(|error| error.exit_code),
                    Some(EXIT_USAGE)
                );
            }
        }
        // --hcloud-token asks for it, it takes no value
        assert!(parse(&["list", "--hcloud-token", "the-token"]).is_err());
    }

    #[test]
    fn the_smtp_password_comes_from_sak_smtp_password_or_is_typed_never_from_an_argument() {
        assert_eq!(
            smtp_password(false, Some("secret".into())).unwrap(),
            "secret"
        );
        // empty: a mail server without a login, or a redeploy takes over the running password
        assert_eq!(smtp_password(false, None).unwrap(), "");
        if !std::io::stdin().is_terminal() {
            assert_eq!(
                smtp_password(true, Some("secret".into()))
                    .err()
                    .map(|error| error.exit_code),
                Some(EXIT_USAGE)
            );
        }
        // --smtp-password asks for it, it takes no value
        assert!(
            parse(&[
                "create",
                "alpha",
                "--hcloud-token",
                "--smtp-password",
                "secret"
            ])
            .is_err()
        );
        assert!(parse(&["create", "alpha", "--hcloud-token", "--smtp-password"]).is_ok());
    }

    #[test]
    fn the_password_comes_from_sak_password_or_is_typed_never_from_an_argument() {
        // SAK_PASSWORD, for both fields
        assert_eq!(
            password(false, true, Some("from the environment".into())).unwrap(),
            ("from the environment".into(), "from the environment".into())
        );
        // asked for, or no SAK_PASSWORD: typed, which needs a terminal; the tests have none
        if !std::io::stdin().is_terminal() {
            for (ask, from_env) in [
                (true, Some("x".to_string())),
                (false, None),
                (false, Some(String::new())),
            ] {
                assert_eq!(
                    password(ask, false, from_env).unwrap_err().exit_code,
                    EXIT_USAGE
                );
            }
        }
    }

    #[test]
    fn the_commands_are_told_from_the_window() {
        assert!(!is_command_line(&[]));
        assert!(!is_command_line(&["-psn_0_1".to_string()]));
        assert!(is_command_line(&["list".to_string()]));
        assert!(is_command_line(&["destroy".to_string()]));
        assert!(is_command_line(&["--help".to_string()]));
        assert_eq!(parse(&[]).unwrap().command, None);
    }

    #[test]
    fn the_arguments_of_the_commands() {
        let cli = parse(&["list", "--hcloud-token"]).unwrap();
        assert_eq!(
            cli.command,
            Some(Command::List(Provider { hcloud_token: true }))
        );

        let cli = parse(&["deploy", "alpha", "--hcloud-token", "--location", "nbg1"]).unwrap();
        let Some(Command::Deploy {
            provider,
            deployment,
        }) = cli.command
        else {
            panic!("a deploy");
        };
        assert!(provider.hcloud_token);
        assert_eq!(deployment.name, "alpha");
        assert_eq!(deployment.location, Location::nbg1);
        // not given: the core resolves them, a deploy to the deployment's own
        assert_eq!(deployment.dns_mode, None);
        assert_eq!(deployment.root_domain, None);
        assert!(!deployment.password);
        assert_eq!(
            deployment
                .deployment(Intent::Deploy, false, ("p".into(), "q".into()), "")
                .password_repeat,
            "q"
        );

        let cli = parse(&["destroy", "alpha", "--hcloud-token", "--password"]).unwrap();
        let Some(Command::Destroy {
            deployment,
            delete_data,
            ..
        }) = cli.command
        else {
            panic!("a destroy");
        };
        assert!(!delete_data);
        assert_eq!(
            deployment
                .deployment(Intent::Destroy, false, Default::default(), "")
                .intent,
            Intent::Destroy
        );
        assert_eq!(deployment.location, Location::nbg1);
        assert!(!deployment.show_log);

        let cli = parse(&[
            "create",
            "alpha",
            "--hcloud-token",
            "--show-log",
            "--dns-mode",
            "domain",
            "--root-domain",
            "pelle.io",
            "--user-mode",
            "multi",
            "--smtp-host",
            "smtp.example.org",
            "--smtp-port",
            "465",
            "--smtp-starttls",
            "false",
        ])
        .unwrap();
        let Some(Command::Create { deployment, .. }) = cli.command else {
            panic!("a create");
        };
        assert!(deployment.show_log);
        assert_eq!(
            deployment
                .deployment(Intent::Create, false, Default::default(), "")
                .dns_mode,
            Some(DnsMode::Domain)
        );
        assert_eq!(
            deployment
                .deployment(Intent::Create, false, Default::default(), "")
                .root_domain
                .as_deref(),
            Some("pelle.io")
        );
        assert_eq!(
            deployment
                .deployment(Intent::Create, false, Default::default(), "")
                .user_mode,
            Some(UserMode::Multi)
        );
        let smtp = deployment
            .deployment(Intent::Create, false, Default::default(), "")
            .smtp
            .unwrap();
        assert_eq!(
            (smtp.host.as_str(), smtp.port, smtp.starttls),
            ("smtp.example.org", 465, false)
        );

        assert!(
            parse(&[
                "create",
                "alpha",
                "--hcloud-token",
                "--password",
                "--dns-mode",
                "whole"
            ])
            .is_err()
        );

        assert!(
            parse(&[
                "create",
                "alpha",
                "--hcloud-token",
                "--password",
                "--location",
                "moon"
            ])
            .is_err()
        );
    }
}
