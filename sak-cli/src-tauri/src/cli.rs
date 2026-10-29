//! the wizard on the command line: `list`, `create` and `deploy` as the first argument run
//! without a window, on the same DeploymentManager, deployments and texts as the pages; this
//! file only parses the arguments and prints. The token comes from --hcloud-token or HCLOUD_TOKEN, a
//! deployment's name, password and location from the argument or SAK_NAME, SAK_PASSWORD and
//! SAK_LOCATION, the same variables the pages take their proposals from. Every line it prints
//! is `<date> <level> <message>`, the level coloured on a terminal, nothing indented. The exit
//! code tells a root domain that cannot be used from any other failure (`core::error`)

use crate::core::checks::{self, CheckEvent, ProviderTokensCheck};
use crate::core::container::ContainerRuntime;
use crate::core::deployments::{
    CreateEvent, DEFAULT_SMTP_PORT, DEFAULT_SMTP_STARTTLS, DeploymentManager, Intent,
    LOCATION_VARIABLE, LOCATIONS, NAME_VARIABLE, NewDeployment, PASSWORD_VARIABLE,
    SMTP_HOST_VARIABLE, SMTP_PASSWORD_VARIABLE, SMTP_PORT_VARIABLE, SMTP_STARTTLS_VARIABLE,
    SMTP_USERNAME_VARIABLE, Smtp, key, parse_flag,
};
use crate::core::error::{EXIT_OK, WizardError};
use crate::core::providers::ProviderType;
use anstyle::{AnsiColor, Style};
use clap::{Args, Parser, Subcommand};
use rust_i18n::t;
use sak_cloud::api::DiffStatus;
use sak_cloud::hetzner::api::Location;
use sak_cloud::hetzner::deployment::DnsMode;
use std::collections::HashMap;
use std::fmt::Display;

/// the exit codes, at the end of the help; a test keeps them those of `core::error`
const EXIT_CODES: &str = "Exit codes:
  0  The command did what it should.
  1  The command failed.
  2  The arguments are wrong.
  3  Domain mode asked, another deployment manages the root domain in domain mode already.
  4  The DNS mode asked is not the one other deployments use the root domain in: a root
     domain has one domain deployment or only subdomain deployments.
  5  A resource of a name the deployment uses belongs to someone else: it lacks the label
     sak.pelle.io/name or the password hash of the deployment. Nothing of it was changed.";

/// SwissArmyKnife: the deployments of a Hetzner Cloud project, listed, created and
/// deployed. Without a command the window opens.
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
    /// firewall and the server
    #[command(after_help = EXIT_CODES)]
    Create {
        #[command(flatten)]
        provider: Provider,
        #[command(flatten)]
        deployment: DeploymentArgs,
    },
    /// Deploy a sak deployment: bring it to what the wizard declares, its server created
    /// anew when it must be, the data volume and the addresses stay
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
    /// Show the running configuration of a deployment, read from its backend with the admin api
    /// token derived from the password; no secret of it is shown
    #[command(after_help = EXIT_CODES)]
    Show {
        #[command(flatten)]
        provider: Provider,
        /// The sak name of the deployment
        #[arg(env = NAME_VARIABLE)]
        name: String,
        /// The password of the deployment
        #[arg(long, env = PASSWORD_VARIABLE, hide_env_values = true)]
        password: String,
    },
}

#[derive(Args, Debug, PartialEq, Eq)]
pub struct Provider {
    /// The API token of the Hetzner Cloud project
    #[arg(long, env = "HCLOUD_TOKEN", hide_env_values = true)]
    hcloud_token: String,
}

#[derive(Args, Debug, PartialEq, Eq)]
pub struct DeploymentArgs {
    /// The sak name of the deployment
    #[arg(env = NAME_VARIABLE)]
    name: String,
    /// The password of the deployment, at least 16 characters; the ssh key is derived from it
    #[arg(long, env = PASSWORD_VARIABLE, hide_env_values = true)]
    password: String,
    /// Where the server, its data volume and its addresses are created
    #[arg(long, env = LOCATION_VARIABLE, default_value = "hel1", value_parser = parse_location)]
    location: Location,
    /// How DNS is set up: domain manages <root domain> and *.<root domain>, subdomain manages
    /// <sak name>.<root domain> and *.<sak name>.<root domain>
    #[arg(long, default_value = "subdomain", value_parser = parse_dns_mode)]
    dns_mode: DnsMode,
    /// The DNS zone of the project the names of the deployment are in, pelle.io; needed by
    /// create and deploy. The zone must exist, SAK does not create it
    #[arg(long)]
    root_domain: Option<String>,
    /// The mail server the backend sends its login links with; without it, no mail server
    #[arg(long, env = SMTP_HOST_VARIABLE)]
    smtp_host: Option<String>,
    /// The port of the mail server
    #[arg(long, env = SMTP_PORT_VARIABLE, default_value_t = DEFAULT_SMTP_PORT)]
    smtp_port: u16,
    /// The user name at the mail server
    #[arg(long, env = SMTP_USERNAME_VARIABLE, default_value = "")]
    smtp_username: String,
    /// The password at the mail server
    #[arg(long, env = SMTP_PASSWORD_VARIABLE, hide_env_values = true, default_value = "")]
    smtp_password: String,
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

fn parse_dns_mode(value: &str) -> Result<DnsMode, String> {
    DnsMode::parse(value).ok_or_else(|| {
        let known: Vec<&str> = DnsMode::ALL.iter().map(|mode| mode.as_str()).collect();
        format!("one of {}", known.join(", "))
    })
}

/// whether the arguments ask for the command line: a command, help or the version as the
/// first argument; anything else (nothing, what a desktop passes) opens the window
pub fn is_command_line(args: &[String]) -> bool {
    matches!(
        args.first().map(String::as_str),
        Some(
            "list"
                | "create"
                | "deploy"
                | "destroy"
                | "config"
                | "--help"
                | "-h"
                | "--version"
                | "-V"
        )
    )
}

/// runs the command, the exit code of the process
pub fn run(command: Command) -> i32 {
    match tauri::async_runtime::block_on(execute(command)) {
        Ok(()) => EXIT_OK,
        Err(error) => {
            log(Level::Error, &error.message);
            error.exit_code
        }
    }
}

impl Provider {
    fn tokens(&self) -> Vec<(ProviderType, String)> {
        vec![(ProviderType::Hetzner, self.hcloud_token.clone())]
    }
}

impl DeploymentArgs {
    fn deployment(&self, intent: Intent, delete_data: bool) -> NewDeployment {
        NewDeployment {
            name: self.name.clone(),
            password: self.password.clone(),
            password_repeat: self.password.clone(),
            location: self.location,
            dns_mode: self.dns_mode,
            root_domain: self.root_domain.clone(),
            intent,
            delete_data,
            smtp: self.smtp_host.as_ref().map(|host| Smtp {
                host: host.clone(),
                port: self.smtp_port,
                username: self.smtp_username.clone(),
                password: self.smtp_password.clone(),
                starttls: self.smtp_starttls,
            }),
        }
    }
}

/// the checks the window runs on startup, each logged, the tokens checked those of the command
async fn check(provider: &Provider) -> Result<ContainerRuntime, String> {
    let tokens = ProviderTokensCheck::new(vec![(
        ProviderType::Hetzner,
        provider.hcloud_token.clone(),
        "--hcloud-token",
    )]);
    let found = tokio::task::spawn_blocking(move || {
        // the progress of a pull ten times a second is for the eye, the log takes each tenth
        let mut logged = 0;
        checks::run(checks::checks(tokens), &mut |event| {
            match event {
                CheckEvent::Started { id, .. } => {
                    log(Level::Info, t!(format!("checks.{id}.running")))
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
                    log(Level::Info, t!(format!("checks.{id}.done"), text = text))
                }
                // the failure itself ends the command, the error is logged once by run
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
    let provider = match &command {
        Command::List(provider)
        | Command::Create { provider, .. }
        | Command::Deploy { provider, .. }
        | Command::Destroy { provider, .. }
        | Command::Config(ConfigCommand::Show { provider, .. }) => provider,
    };
    let runtime = check(provider).await?;
    match command {
        Command::List(provider) => list(&manager, &provider).await,
        Command::Config(ConfigCommand::Show {
            provider,
            name,
            password,
        }) => show_config(&manager, &provider, &name, &password).await,
        Command::Create {
            provider,
            deployment,
        } => {
            deploy(
                &manager,
                &provider,
                &deployment.deployment(Intent::Create, false),
                &runtime,
                deployment.show_log,
            )
            .await
        }
        Command::Deploy {
            provider,
            deployment,
        } => {
            deploy(
                &manager,
                &provider,
                &deployment.deployment(Intent::Deploy, false),
                &runtime,
                deployment.show_log,
            )
            .await
        }
        Command::Destroy {
            provider,
            deployment,
            delete_data,
        } => {
            deploy(
                &manager,
                &provider,
                &deployment.deployment(Intent::Destroy, delete_data),
                &runtime,
                deployment.show_log,
            )
            .await
        }
    }
}

async fn list(manager: &DeploymentManager, provider: &Provider) -> Result<(), WizardError> {
    let deployments = manager.list(provider.tokens()).await?;
    if deployments.is_empty() {
        log(Level::Info, t!("ui.deployments.empty"));
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
                t!(format!("ui.deployments.status.{}", key(&deployment.status))),
                location,
                deployment.provider.name,
                deployment.frontend_url.as_deref().unwrap_or_default()
            )
            .trim_end(),
        );
        for resource in deployment.resources {
            let kind = t!(format!("ui.deployments.resource.{}", key(&resource.kind)));
            let detail = match resource.status {
                DiffStatus::Missing => t!("ui.deployments.missing").to_string(),
                _ => resource.detail.unwrap_or_default(),
            };
            let resource_name = format!("{kind} '{}'", resource.name);
            log(Level::Info, format!("{resource_name} {detail}"));
            for change in resource.changes {
                log(Level::Warn, format!("{resource_name} {change}"));
            }
        }
    }
    Ok(())
}

/// the running configuration of the deployment, a line for each setting
async fn show_config(
    manager: &DeploymentManager,
    provider: &Provider,
    name: &str,
    password: &str,
) -> Result<(), WizardError> {
    let (url, config) = manager
        .running_config(provider.tokens(), name, password)
        .await?;
    log(
        Level::Info,
        t!("config.shown", name = name.trim(), url = url.as_str()),
    );
    for line in config_lines(&config) {
        log(Level::Info, line);
    }
    Ok(())
}

/// `smtp.host smtp.example.org`, … : the settings as `<key> <value>`, no secret among them
fn config_lines(config: &crate::core::config::RunningConfig) -> Vec<String> {
    let Some(smtp) = &config.smtp else {
        return vec![t!("config.no_smtp").to_string()];
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

/// plans, prints the plan and applies it, each resource printed as it is done: what the
/// plan page and its create do; the output of ansible only with `show_log`
async fn deploy(
    manager: &DeploymentManager,
    provider: &Provider,
    deployment: &NewDeployment,
    runtime: &ContainerRuntime,
    show_log: bool,
) -> Result<(), WizardError> {
    let plan = manager.plan(provider.tokens(), deployment).await?;
    let intro = match deployment.intent {
        Intent::Create => t!("ui.plan.intro"),
        Intent::Deploy => t!("ui.plan.intro_deploy"),
        Intent::Destroy => t!("ui.plan.intro_destroy"),
    };
    log(Level::Info, format!("{intro} {}", plan.name));
    for resource in &plan.resources {
        let what = match deployment.intent {
            _ if resource.remove => "will_destroy".to_string(),
            Intent::Destroy if resource.keep => "keep".to_string(),
            Intent::Destroy if resource.status == DiffStatus::Missing => "gone".to_string(),
            Intent::Destroy => "will_destroy".to_string(),
            _ if resource.recreate => "recreate".to_string(),
            _ => key(&resource.status),
        };
        // what a destroy takes away is worth a second look
        let level = if what == "will_destroy" {
            Level::Warn
        } else {
            Level::Info
        };
        log(
            level,
            format!("{} {}", resource.resource, t!(format!("ui.plan.{what}"))),
        );
        for change in &resource.changes {
            log(Level::Warn, format!("{} {change}", resource.resource));
        }
    }

    // what is done to a resource, the verb first: `created server 'alpha'`
    // what is being done to a resource as its apply starts, after what the plan found
    let working: HashMap<String, &str> = plan
        .resources
        .iter()
        .map(|resource| {
            let key = working_key(deployment.intent, &resource.status, resource.recreate);
            (resource.resource.clone(), key)
        })
        .collect();
    let mut report = |event: CreateEvent| match event {
        CreateEvent::Started { resource } => {
            let key = working.get(&resource).copied().unwrap_or("cli.working");
            log(Level::Info, t!(key, resource = resource))
        }
        CreateEvent::Applied { resource, applied } => log(
            Level::Info,
            t!(
                format!("cli.applied.{}", key(&applied)),
                resource = resource
            ),
        ),
        CreateEvent::Destroyed { resource, gone } => {
            let what = if gone { "destroyed" } else { "gone" };
            log(
                Level::Info,
                t!(format!("cli.applied.{what}"), resource = resource),
            )
        }
        CreateEvent::Provisioning { message } => log(Level::Info, message),
        // ansible indents and spaces out its output, the log keeps only what it says
        CreateEvent::Output { line } => {
            if show_log && !line.trim().is_empty() {
                log(Level::Info, format!("[ansible] {}", line.trim()))
            }
        }
    };
    manager
        .create(provider.tokens(), deployment, runtime, &mut report)
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

/// the text of a resource whose apply starts: creating what is missing, updating or recreating
/// what changed, checking what is up to date, destroying on a destroy
fn working_key(intent: Intent, status: &DiffStatus, recreate: bool) -> &'static str {
    match (intent, status) {
        (Intent::Destroy, _) => "cli.working_destroy",
        (_, DiffStatus::Missing | DiffStatus::ParentMissing) => "cli.working",
        (_, DiffStatus::UpToDate) => "cli.working_check",
        _ if recreate => "cli.working_recreate",
        _ => "cli.working_update",
    }
}

/// how much a line of the log matters, its colour on a terminal
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Level {
    Info,
    /// a change the plan found
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

/// prints a line of the log, an error to stderr; anstream drops the colour when the output
/// is no terminal or NO_COLOR is set
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
        EXIT_DOMAIN_TAKEN, EXIT_FAILED, EXIT_MIXED_DNS_MODES, EXIT_NOT_OWNED, EXIT_USAGE,
    };

    fn parse(words: &[&str]) -> Result<Cli, clap::Error> {
        Cli::try_parse_from(std::iter::once("sakctl").chain(words.iter().copied()))
    }

    #[test]
    fn the_start_of_an_apply_says_what_is_done_to_the_resource() {
        assert_eq!(
            working_key(Intent::Create, &DiffStatus::Missing, false),
            "cli.working"
        );
        assert_eq!(
            working_key(Intent::Deploy, &DiffStatus::HasChanges, false),
            "cli.working_update"
        );
        assert_eq!(
            working_key(Intent::Deploy, &DiffStatus::HasChanges, true),
            "cli.working_recreate"
        );
        assert_eq!(
            working_key(Intent::Deploy, &DiffStatus::UpToDate, false),
            "cli.working_check"
        );
        assert_eq!(
            working_key(Intent::Destroy, &DiffStatus::UpToDate, false),
            "cli.working_destroy"
        );
    }

    #[test]
    fn a_log_line_is_date_level_message_with_the_level_coloured() {
        let line = log_line("2026-10-28 14:03:12", Level::Info, "created server 'alpha'");
        assert_eq!(
            anstream::adapter::strip_str(&line).to_string(),
            "2026-10-28 14:03:12 INFO created server 'alpha'"
        );
        assert!(line.contains("\x1b[1m\x1b[32mINFO\x1b[0m created"));
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
        let cli = parse(&[
            "config",
            "show",
            "alpha",
            "--hcloud-token",
            "t",
            "--password",
            "p",
        ])
        .unwrap();
        let Some(Command::Config(ConfigCommand::Show { name, password, .. })) = cli.command else {
            panic!("config show");
        };
        assert_eq!((name.as_str(), password.as_str()), ("alpha", "p"));
        assert!(is_command_line(&["config".to_string()]));

        let config = crate::core::config::RunningConfig {
            smtp: Some(crate::core::config::RunningSmtp {
                host: "smtp.example.org".to_string(),
                port: 465,
                username: None,
                password_set: true,
                from: "noreply@example.org".to_string(),
                tls: true,
                starttls: false,
            }),
        };
        let lines = config_lines(&config);
        assert!(lines.contains(&"smtp.host smtp.example.org".to_string()));
        assert!(lines.contains(&"smtp.username -".to_string()));
        assert!(lines.contains(&"smtp.password set".to_string()));
        assert_eq!(
            config_lines(&crate::core::config::RunningConfig { smtp: None }),
            vec![t!("config.no_smtp").to_string()]
        );
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
        let cli = parse(&["list", "--hcloud-token", "t"]).unwrap();
        assert_eq!(
            cli.command,
            Some(Command::List(Provider {
                hcloud_token: "t".to_string()
            }))
        );

        let cli = parse(&[
            "deploy",
            "alpha",
            "--hcloud-token",
            "t",
            "--password",
            "a password of sixteen",
            "--location",
            "nbg1",
        ])
        .unwrap();
        let Some(Command::Deploy {
            provider,
            deployment,
        }) = cli.command
        else {
            panic!("a deploy");
        };
        assert_eq!(provider.hcloud_token, "t");
        assert_eq!(deployment.name, "alpha");
        assert_eq!(deployment.location, Location::nbg1);
        assert_eq!(deployment.dns_mode, DnsMode::Subdomain);
        assert_eq!(deployment.root_domain, None);
        assert_eq!(
            deployment.deployment(Intent::Deploy, false).password_repeat,
            "a password of sixteen"
        );

        let cli = parse(&["destroy", "alpha", "--hcloud-token", "t", "--password", "p"]).unwrap();
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
            deployment.deployment(Intent::Destroy, false).intent,
            Intent::Destroy
        );
        assert_eq!(deployment.location, Location::hel1);
        assert!(!deployment.show_log);

        let cli = parse(&[
            "create",
            "alpha",
            "--hcloud-token",
            "t",
            "--password",
            "p",
            "--show-log",
            "--dns-mode",
            "domain",
            "--root-domain",
            "pelle.io",
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
            deployment.deployment(Intent::Create, false).dns_mode,
            DnsMode::Domain
        );
        assert_eq!(
            deployment
                .deployment(Intent::Create, false)
                .root_domain
                .as_deref(),
            Some("pelle.io")
        );
        let smtp = deployment.deployment(Intent::Create, false).smtp.unwrap();
        assert_eq!(
            (smtp.host.as_str(), smtp.port, smtp.starttls),
            ("smtp.example.org", 465, false)
        );

        assert!(
            parse(&[
                "create",
                "alpha",
                "--hcloud-token",
                "t",
                "--password",
                "p",
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
                "t",
                "--password",
                "p",
                "--location",
                "moon"
            ])
            .is_err()
        );
    }
}
