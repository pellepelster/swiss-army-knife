//! the wizard on the command line: `list`, `create` and `deploy` as the first argument run
//! without a window, on the same DeploymentManager, deployments and texts as the pages; this
//! file only parses the arguments and prints. The token comes from --hcloud-token or HCLOUD_TOKEN, a
//! deployment's name, password and location from the argument or SAK_NAME, SAK_PASSWORD and
//! SAK_LOCATION, the same variables the pages take their proposals from

use crate::core::container::{self, ContainerRuntime};
use crate::core::deployments::{
    CreateEvent, DeploymentManager, Intent, LOCATION_VARIABLE, LOCATIONS, NAME_VARIABLE,
    NewDeployment, PASSWORD_VARIABLE, key,
};
use crate::core::providers::ProviderType;
use clap::{Args, Parser, Subcommand};
use rust_i18n::t;
use sak_cloud::api::DiffStatus;
use sak_cloud::hetzner::api::Location;

/// SwissArmyKnife: the deployments of a Hetzner Cloud project, listed, created and
/// deployed. Without a command the window opens.
#[derive(Parser, Debug)]
#[command(name = "sak-wizard", version, disable_help_subcommand = true)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Option<Command>,
}

#[derive(Subcommand, Debug, PartialEq, Eq)]
pub enum Command {
    /// List the sak deployments of the project with the state of their resources
    List(Provider),
    /// Create a sak deployment: an ssh key, a data volume, a primary ipv4 and ipv6, a
    /// firewall and the server
    Create {
        #[command(flatten)]
        provider: Provider,
        #[command(flatten)]
        deployment: DeploymentArgs,
    },
    /// Deploy a sak deployment: bring it to what the wizard declares, its server created
    /// anew when it must be, the data volume and the addresses stay
    Deploy {
        #[command(flatten)]
        provider: Provider,
        #[command(flatten)]
        deployment: DeploymentArgs,
    },
    /// Destroy a sak deployment: its server, addresses, firewall and ssh key are removed,
    /// the data volume stays
    Destroy {
        #[command(flatten)]
        provider: Provider,
        #[command(flatten)]
        deployment: DeploymentArgs,
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

/// whether the arguments ask for the command line: a command, help or the version as the
/// first argument; anything else (nothing, what a desktop passes) opens the window
pub fn is_command_line(args: &[String]) -> bool {
    matches!(
        args.first().map(String::as_str),
        Some("list" | "create" | "deploy" | "destroy" | "--help" | "-h" | "--version" | "-V")
    )
}

/// runs the command, the exit code of the process
pub fn run(command: Command) -> i32 {
    match tauri::async_runtime::block_on(execute(command)) {
        Ok(()) => 0,
        Err(message) => {
            eprintln!("{message}");
            1
        }
    }
}

impl Provider {
    fn tokens(&self) -> Vec<(ProviderType, String)> {
        vec![(ProviderType::Hetzner, self.hcloud_token.clone())]
    }
}

impl DeploymentArgs {
    fn deployment(&self, intent: Intent) -> NewDeployment {
        NewDeployment {
            name: self.name.clone(),
            password: self.password.clone(),
            password_repeat: self.password.clone(),
            location: self.location,
            intent,
        }
    }
}

async fn execute(command: Command) -> Result<(), String> {
    let manager = DeploymentManager;
    match command {
        Command::List(provider) => list(&manager, &provider).await,
        Command::Create {
            provider,
            deployment,
        } => deploy(&manager, &provider, &deployment.deployment(Intent::Create)).await,
        Command::Deploy {
            provider,
            deployment,
        } => deploy(&manager, &provider, &deployment.deployment(Intent::Deploy)).await,
        Command::Destroy {
            provider,
            deployment,
        } => deploy(&manager, &provider, &deployment.deployment(Intent::Destroy)).await,
    }
}

async fn list(manager: &DeploymentManager, provider: &Provider) -> Result<(), String> {
    let deployments = manager.list(provider.tokens()).await?;
    if deployments.is_empty() {
        println!("{}", t!("ui.deployments.empty"));
    }
    for deployment in deployments {
        let location = deployment
            .location
            .map(|location| location.to_string())
            .unwrap_or_default();
        println!(
            "{}  {}  {}  {}",
            deployment.name,
            t!(format!("ui.deployments.status.{}", key(&deployment.status))),
            location,
            deployment.provider.name
        );
        for resource in deployment.resources {
            let kind = t!(format!("ui.deployments.resource.{}", key(&resource.kind)));
            let detail = match resource.status {
                DiffStatus::Missing => t!("ui.deployments.missing").to_string(),
                _ => resource.detail.unwrap_or_default(),
            };
            println!("  {kind} '{}'  {detail}", resource.name);
            for change in resource.changes {
                println!("    {change}");
            }
        }
    }
    Ok(())
}

/// plans, prints the plan and applies it, each resource printed as it is done: what the
/// plan page and its create do
async fn deploy(
    manager: &DeploymentManager,
    provider: &Provider,
    deployment: &NewDeployment,
) -> Result<(), String> {
    let plan = manager.plan(provider.tokens(), deployment).await?;
    let intro = match deployment.intent {
        Intent::Create => t!("ui.plan.intro"),
        Intent::Deploy => t!("ui.plan.intro_deploy"),
        Intent::Destroy => t!("ui.plan.intro_destroy"),
    };
    println!("{intro} {}", plan.name);
    for resource in &plan.resources {
        let what = match deployment.intent {
            Intent::Destroy if resource.keep => "keep".to_string(),
            Intent::Destroy if resource.status == DiffStatus::Missing => "gone".to_string(),
            Intent::Destroy => "will_destroy".to_string(),
            _ if resource.recreate => "recreate".to_string(),
            _ => key(&resource.status),
        };
        println!("  {}  {}", resource.resource, t!(format!("ui.plan.{what}")));
        for change in &resource.changes {
            println!("    {change}");
        }
    }

    // the server is provisioned in the image of the tools, docker or podman runs it
    let runtime = ContainerRuntime::default();
    let (found, _) = container::find(&["docker", "podman"])?;
    runtime.set(found);
    let working = match deployment.intent {
        Intent::Destroy => t!("ui.plan.working_destroy"),
        _ => t!("ui.plan.working"),
    };
    let mut report = |event: CreateEvent| match event {
        CreateEvent::Started { resource } => println!("  {resource}  {working}"),
        CreateEvent::Applied { resource, applied } => {
            println!(
                "  {resource}  {}",
                t!(format!("ui.plan.done.{}", key(&applied)))
            )
        }
        CreateEvent::Destroyed { resource, gone } => {
            let what = if gone { "done.destroyed" } else { "gone" };
            println!("  {resource}  {}", t!(format!("ui.plan.{what}")))
        }
        CreateEvent::Output { line } => println!("{line}"),
    };
    manager
        .create(provider.tokens(), deployment, &runtime, &mut report)
        .await?;
    let done = match deployment.intent {
        Intent::Destroy => t!("cli.destroyed", name = deployment.name),
        _ => t!("cli.done", name = deployment.name),
    };
    println!("{done}");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(words: &[&str]) -> Result<Cli, clap::Error> {
        Cli::try_parse_from(std::iter::once("sak-wizard").chain(words.iter().copied()))
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
        assert_eq!(
            deployment.deployment(Intent::Deploy).password_repeat,
            "a password of sixteen"
        );

        let cli = parse(&["destroy", "alpha", "--hcloud-token", "t", "--password", "p"]).unwrap();
        let Some(Command::Destroy { deployment, .. }) = cli.command else {
            panic!("a destroy");
        };
        assert_eq!(
            deployment.deployment(Intent::Destroy).intent,
            Intent::Destroy
        );
        assert_eq!(deployment.location, Location::hel1);

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
