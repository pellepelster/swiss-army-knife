//! The command line of sakctl. A command as the first argument (`list`, `create`, `deploy`,
//! `destroy`, `config`) runs without a window, on the same core and texts as the window. This file
//! only parses the arguments and prints. The Hetzner token comes from HCLOUD_TOKEN or is typed
//! (`--hcloud-token`). The name and the location of a deployment come from the argument or from
//! SAK_NAME and SAK_LOCATION. The password comes from SAK_PASSWORD or is typed (`--password`),
//! never from an argument. Every line it prints is `<date> <level> <message>`, the level in color
//! on a terminal. The exit code tells the failures apart (`core::error`)

use crate::core::checks::{self, CheckEvent, ProviderTokensCheck};
use crate::core::container::ContainerRuntime;
use crate::core::deployments::{
    ADMIN_EMAIL_VARIABLE, AcmeCa, CreateEvent, DEFAULT_SMTP_PORT, DEFAULT_SMTP_STARTTLS,
    DeploymentManager, Intent, LOCATION_VARIABLE, LOCATIONS, NAME_VARIABLE, NewDeployment,
    PASSWORD_VARIABLE, SMTP_HOST_VARIABLE, SMTP_PASSWORD_VARIABLE, SMTP_PORT_VARIABLE,
    SMTP_STARTTLS_VARIABLE, SMTP_USERNAME_VARIABLE, Smtp, TaskOutcome, USER_MODE_VARIABLE,
    UserMode, applied_key, complete, destroyed_key, key, parse_flag, resource_status,
};
use crate::core::error::{EXIT_OK, EXIT_USAGE, WizardError};
use crate::core::providers::ProviderType;
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
     data or address. Nothing was changed.";

/// SwissArmyKnife: list, create, deploy and destroy the deployments of a Hetzner Cloud project,
/// and show their running configuration. Without a command the window opens.
#[derive(Parser, Debug)]
#[command(
    name = "sakctl",
    version,
    disable_help_subcommand = true,
    after_help = EXIT_CODES
)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Option<Command>,
}

#[derive(Subcommand, Debug, PartialEq, Eq)]
pub enum Command {
    /// List the sak deployments of the project with the state of their resources
    #[command(after_help = EXIT_CODES)]
    List(Provider),
    /// Create a sak deployment: an ssh key, a data volume, a primary ipv4 and ipv6, a
    /// firewall, the server and its DNS records, then provision the server
    #[command(after_help = EXIT_CODES)]
    Create {
        #[command(flatten)]
        provider: Provider,
        #[command(flatten)]
        deployment: DeploymentArgs,
    },
    /// Deploy a sak deployment: bring it to what it declares and provision the server again.
    /// The server is created anew when it must be; the data volume and the addresses stay
    #[command(after_help = EXIT_CODES)]
    Deploy {
        #[command(flatten)]
        provider: Provider,
        #[command(flatten)]
        deployment: DeploymentArgs,
    },
    /// The configuration of a running sak deployment
    #[command(subcommand)]
    Config(ConfigCommand),
    /// Open an ssh session as root on the server of a sak deployment, or run a command there
    /// (after --). It runs in the image of the tools with the key derived from the password, the
    /// server verified by its derived host key; the key is never written
    #[command(after_help = EXIT_CODES)]
    Ssh {
        #[command(flatten)]
        provider: Provider,
        /// The name of the deployment
        #[arg(env = NAME_VARIABLE)]
        name: String,
        /// Ask for the password of the deployment on the terminal, even when SAK_PASSWORD is set
        #[arg(long)]
        password: bool,
        /// A command to run on the server instead of a session
        #[arg(last = true)]
        command: Vec<String>,
    },
    /// Destroy a sak deployment: its DNS records, server, addresses, firewall and ssh key are
    /// removed, the data volume stays unless --delete-data is given. A deployment that is gone
    /// is destroyed already
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
}

#[derive(Subcommand, Debug, PartialEq, Eq)]
pub enum ConfigCommand {
    /// Show the running configuration of a deployment, read from its backend with a request signed
    /// with the key derived from the password; no secret of it is shown
    #[command(after_help = EXIT_CODES)]
    Show {
        #[command(flatten)]
        provider: Provider,
        /// The name of the deployment
        #[arg(env = NAME_VARIABLE)]
        name: String,
        /// Ask for the password of the deployment on the terminal, even when SAK_PASSWORD is set
        #[arg(long)]
        password: bool,
    },
}

#[derive(Args, Debug, PartialEq, Eq)]
pub struct Provider {
    /// Ask for the API token of the Hetzner Cloud project on the terminal, even when HCLOUD_TOKEN
    /// is set. The token is never an argument: it comes from HCLOUD_TOKEN or is typed, without
    /// echo
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
    /// Ask for the password of the deployment on the terminal, even when SAK_PASSWORD is set. The
    /// password (at least 16 characters, the keys of the deployment are derived from it) is never
    /// an argument: it comes from SAK_PASSWORD or is typed, without echo
    #[arg(long)]
    password: bool,
    /// Where the server, its data volume and its addresses are created
    #[arg(long, env = LOCATION_VARIABLE, default_value = "nbg1", value_parser = parse_location)]
    location: Location,
    /// How DNS is set up: domain manages <root domain> and *.<root domain>, subdomain manages
    /// <name>.<root domain> and *.<name>.<root domain>. Not given: subdomain for a create,
    /// the deployment's own for a deploy
    #[arg(long, value_parser = parse_dns_mode)]
    dns_mode: Option<DnsMode>,
    /// The DNS zone of the project the names of the deployment are in, pelle.io; needed by a
    /// create, a deploy takes the deployment's own when not given. The zone must exist, SAK does
    /// not create it
    #[arg(long)]
    root_domain: Option<String>,
    /// Who signs in, needed by create and deploy: single, one user, the admin, who signs in with
    /// the password of the deployment; multi, users sign in by e-mail and the admin also with the
    /// password, needs --admin-email and --smtp-host
    #[arg(long, env = USER_MODE_VARIABLE, value_parser = parse_user_mode)]
    user_mode: Option<UserMode>,
    /// The e-mail address of the admin, the first user of the e-mail login (multi user)
    #[arg(long, env = ADMIN_EMAIL_VARIABLE)]
    admin_email: Option<String>,
    /// The mail server the backend sends its login links with (multi user)
    #[arg(long, env = SMTP_HOST_VARIABLE)]
    smtp_host: Option<String>,
    /// The port of the mail server
    #[arg(long, env = SMTP_PORT_VARIABLE, default_value_t = DEFAULT_SMTP_PORT)]
    smtp_port: u16,
    /// The user name at the mail server
    #[arg(long, env = SMTP_USERNAME_VARIABLE, default_value = "")]
    smtp_username: String,
    /// Where the certificates of the deployment come from: production, or staging, the staging ca
    /// of let's encrypt for test deployments (sakctl then trusts its roots). Not given: the
    /// deployment's own for a deploy, production for a create
    #[arg(long, value_parser = parse_acme_ca)]
    acme_ca: Option<AcmeCa>,
    /// Ask for the password at the mail server on the terminal, even when SAK_SMTP_PASSWORD is
    /// set. It is never an argument: it comes from SAK_SMTP_PASSWORD or is typed, without echo;
    /// without either the mail server has none, a redeploy takes over the running one
    #[arg(long)]
    smtp_password: bool,
    /// Whether the connection to the mail server is upgraded with STARTTLS (true or false)
    #[arg(long,
        action = clap::ArgAction::Set, env = SMTP_STARTTLS_VARIABLE, default_value_t = DEFAULT_SMTP_STARTTLS, value_parser = parse_flag)]
    smtp_starttls: bool,
    /// Stream the output of ansible while it provisions the server, each line prefixed with
    /// [ansible]
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
    match tauri::async_runtime::block_on(execute(command)) {
        Ok(()) => EXIT_OK,
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
        Command::List(_) => None,
        Command::Create { deployment, .. } => {
            Some(password(deployment.password, true, password_variable())?)
        }
        Command::Deploy { deployment, .. } | Command::Destroy { deployment, .. } => {
            Some(password(deployment.password, false, password_variable())?)
        }
        Command::Config(ConfigCommand::Show { password: ask, .. })
        | Command::Ssh { password: ask, .. } => Some(password(*ask, false, password_variable())?),
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
        | Command::Ssh { provider, .. } => provider,
    };
    let token = hcloud_token(provider.hcloud_token, std::env::var("HCLOUD_TOKEN").ok())?;
    let runtime = check(&token).await?;
    match command {
        Command::List(_) => list(&manager, &token).await,
        Command::Config(ConfigCommand::Show { name, .. }) => {
            show_config(&manager, &token, &name, &password.0).await
        }
        Command::Ssh { name, command, .. } => {
            ssh(&manager, &token, &runtime, &name, &password.0, &command).await
        }
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
    let plan = manager.plan_prepared(&prepared, deployment).await?;
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
        CreateEvent::Provisioned { .. } => log(
            Level::Info,
            t!("cli.provisioned", name = deployment.name.trim()),
        ),
        CreateEvent::ProvisioningUpToDate { .. } => log(
            Level::Info,
            t!("cli.provisioning_up_to_date", name = deployment.name.trim()),
        ),
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
        EXIT_RECREATE_REFUSED, EXIT_USAGE,
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
