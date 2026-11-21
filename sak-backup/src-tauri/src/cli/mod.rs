//! the command line of sakbackup: with a command as the first argument it runs without a window,
//! on the same instance, repositories and backups as the window. Its session is the one of
//! SAK_BACKUP_ADDRESS and SAK_BACKUP_SESSION_TOKEN when both are set (scripts and tests), else the
//! sign-in of the window kept in the keychain for the SAK address used last (DESKTOP-1). The
//! password of a repository comes from SAK_BACKUP_REPO_PASSWORD, the keychain or is typed, never from
//! an argument (BACKUP-3). What it does is core/, as for the window. Exit codes: 0 done, 1 failed,
//! 2 wrong arguments

use crate::core::api::{self, PLANS, REPOSITORIES, host};
use crate::core::passwords::{Given, PasswordStore};
use crate::core::run::{RunError, RunEvent, Runs};
use crate::core::{restore, run, snapshots};
use clap::{Parser, Subcommand};
use rust_i18n::t;
use sak_desktop_shared::api::ApiError;
use sak_desktop_shared::cli::SessionError;
use sak_desktop_shared::session::SessionManager;
use serde_json::{Value, json};
use std::io::IsTerminal;
use std::path::PathBuf;

/// the identifier of the app, its config directory and its entry in the keychain (tauri.conf.json)
const IDENTIFIER: &str = "io.pelle.sak.backup";

pub const ADDRESS_VARIABLE: &str = "SAK_BACKUP_ADDRESS";
pub const SESSION_VARIABLE: &str = "SAK_BACKUP_SESSION_TOKEN";

/// The backups of a SAK instance with restic. Without a command, the window opens.
#[derive(Parser)]
#[command(name = "sakbackup", version)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Command,
}

#[derive(Subcommand)]
pub enum Command {
    /// The repositories of restic the SAK instance keeps
    #[command(subcommand)]
    Repo(RepoCommand),
    /// The backup plans: a repository and paths of this computer
    #[command(subcommand)]
    Plan(PlanCommand),
    /// The snapshots of a repository, of every computer
    #[command(subcommand)]
    Snapshot(SnapshotCommand),
    /// Back up a backup plan of this computer now; the first run creates its repository with a typed password, or with --init
    Run {
        /// The name of the backup plan
        plan: String,
        /// Create the repository with the password of SAK_BACKUP_REPO_PASSWORD when it has no backup yet
        #[arg(long)]
        init: bool,
    },
    /// Restore the newest snapshot of a backup plan of this computer, by default where its paths were
    Restore {
        /// The name of the backup plan
        plan: String,
        /// The directory the paths are restored below, / puts them back where they were
        #[arg(long, default_value = "/")]
        target: PathBuf,
    },
}

/// how many snapshots a run of the backup plan keeps, each from 1 to 1000 (BACKUP-8); restic removes
/// the others after a backup
#[derive(clap::Args, Clone, Debug, Default)]
pub struct KeepArgs {
    /// Keep the last n snapshots
    #[arg(long, value_name = "N")]
    keep_last: Option<u64>,
    /// Keep the last snapshot of each of the last n days
    #[arg(long, value_name = "N")]
    keep_daily: Option<u64>,
    /// Keep the last snapshot of each of the last n weeks
    #[arg(long, value_name = "N")]
    keep_weekly: Option<u64>,
    /// Keep the last snapshot of each of the last n months
    #[arg(long, value_name = "N")]
    keep_monthly: Option<u64>,
    /// Keep the last snapshot of each of the last n years
    #[arg(long, value_name = "N")]
    keep_yearly: Option<u64>,
    /// Keep every snapshot
    #[arg(long, conflicts_with_all = ["keep_last", "keep_daily", "keep_weekly", "keep_monthly", "keep_yearly"])]
    keep_all: bool,
}

impl KeepArgs {
    /// the setting as the api has it; none when nothing is given and not --keep-all
    fn json(&self) -> Option<Value> {
        let given = [
            self.keep_last,
            self.keep_daily,
            self.keep_weekly,
            self.keep_monthly,
            self.keep_yearly,
        ];
        if !self.keep_all && given.iter().all(Option::is_none) {
            return None;
        }
        Some(json!({
            "keepLast": self.keep_last,
            "keepDaily": self.keep_daily,
            "keepWeekly": self.keep_weekly,
            "keepMonthly": self.keep_monthly,
            "keepYearly": self.keep_yearly,
        }))
    }
}

/// what a new backup plan keeps without --keep-*: 7 daily, 4 weekly and 12 monthly snapshots, as in the
/// window
fn default_keep() -> Value {
    json!({ "keepDaily": 7, "keepWeekly": 4, "keepMonthly": 12 })
}

/// the backup plan with `keep` in place of what it keeps
fn with_keep(mut plan: Value, keep: &Value) -> Value {
    for (name, value) in keep.as_object().into_iter().flatten() {
        plan[name] = value.clone();
    }
    plan
}

#[derive(Subcommand)]
pub enum SnapshotCommand {
    /// List the snapshots of a repository, the newest first
    List {
        /// The name of the repository
        #[arg(long)]
        repo: String,
    },
    /// Delete a snapshot of a repository, and the data no other snapshot needs
    Delete {
        /// The id of the snapshot, as `snapshot list` shows it
        id: String,
        /// The name of the repository
        #[arg(long)]
        repo: String,
    },
}

#[derive(Subcommand)]
pub enum RepoCommand {
    /// Create a repository on the SAK instance
    Create {
        /// Lower case letters, digits and -
        name: String,
    },
    /// List the repositories with their sizes
    List,
}

#[derive(Subcommand)]
pub enum PlanCommand {
    /// Create a backup plan of paths of this computer into a repository; without --keep-* it keeps
    /// 7 daily, 4 weekly and 12 monthly snapshots
    Create {
        /// Lower case letters, digits and -
        name: String,
        /// The name of the repository
        #[arg(long)]
        repo: String,
        /// The files and folders to back up
        #[arg(required = true)]
        paths: Vec<PathBuf>,
        #[command(flatten)]
        keep: KeepArgs,
    },
    /// Change how many snapshots a backup plan keeps; what is not given is no longer kept by it
    Set {
        /// The name of the backup plan
        name: String,
        #[command(flatten)]
        keep: KeepArgs,
    },
    /// List the backup plans of every computer
    List,
}

/// runs the command, its exit code
pub fn run(cli: Cli) -> i32 {
    match tauri::async_runtime::block_on(command(cli.command)) {
        Ok(()) => 0,
        Err(message) => {
            eprintln!("{message}");
            1
        }
    }
}

async fn command(command: Command) -> Result<(), String> {
    let sessions = session().await?;
    let passwords = PasswordStore::default();
    match command {
        Command::Repo(RepoCommand::Create { name }) => {
            let created = api::create_repository(&sessions, &name)
                .await
                .map_err(|error| api_message(&error))?;
            println!(
                "{}",
                t!(
                    "cli.repository_created",
                    name = text(&created["name"]),
                    url = text(&created["url"])
                )
            );
        }
        Command::Repo(RepoCommand::List) => {
            let repositories = api::repositories(&sessions)
                .await
                .map_err(|error| api_message(&error))?;
            for repository in repositories {
                println!(
                    "{}\t{}\t{}",
                    text(&repository["name"]),
                    repository["size"],
                    text(&repository["url"])
                );
            }
        }
        Command::Plan(PlanCommand::Create {
            name,
            repo,
            paths,
            keep,
        }) => {
            let repository =
                by_name(&sessions, REPOSITORIES, &repo, "cli.unknown_repository").await?;
            let paths: Vec<String> = paths
                .iter()
                .map(|path| {
                    std::path::absolute(path)
                        .map(|path| normalized(&path).to_string_lossy().into_owned())
                })
                .collect::<Result<_, _>>()
                .map_err(|error| error.to_string())?;
            let host = host();
            let plan = with_keep(
                json!({ "name": name, "repositoryId": repository["id"], "host": host, "paths": paths }),
                &keep.json().unwrap_or_else(default_keep),
            );
            let created = api::create_plan(&sessions, &plan)
                .await
                .map_err(|error| api_message(&error))?;
            println!(
                "{}",
                t!(
                    "cli.plan_created",
                    name = text(&created["name"]),
                    repository = repo,
                    host = host
                )
            );
        }
        Command::Plan(PlanCommand::Set { name, keep }) => {
            let keep = keep.json().ok_or_else(|| t!("cli.no_keep").to_string())?;
            let plan = by_name(&sessions, PLANS, &name, "cli.unknown_plan").await?;
            let id = plan["id"].as_u64().unwrap_or_default();
            // the values of the plan the api takes, with the new setting
            let values = json!({
                "name": plan["name"],
                "repositoryId": plan["repositoryId"],
                "host": plan["host"],
                "paths": plan["paths"],
            });
            api::update_plan(&sessions, id, &with_keep(values, &keep))
                .await
                .map_err(|error| api_message(&error))?;
            println!("{}", t!("cli.plan_changed", name = name));
        }
        Command::Plan(PlanCommand::List) => {
            let plans = api::plans(&sessions)
                .await
                .map_err(|error| api_message(&error))?;
            for plan in plans {
                let paths: Vec<String> = plan["paths"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .map(text)
                    .collect();
                println!(
                    "{}\t{}\t{}",
                    text(&plan["name"]),
                    text(&plan["host"]),
                    paths.join(" ")
                );
            }
        }
        Command::Run { plan, init } => {
            let id = id_of(&sessions, PLANS, &plan, "cli.unknown_plan").await?;
            let runs = Runs::default();
            let summary = with_password(|given, typed_init| {
                run::back_up(
                    &sessions,
                    &runs,
                    &passwords,
                    id,
                    given,
                    typed_init || init,
                    |event| match event {
                        RunEvent::Initializing => println!("{}", t!("cli.initializing")),
                        RunEvent::Expiring => println!("{}", t!("cli.expiring")),
                        RunEvent::Skipped { message } => {
                            eprintln!("{}", t!("cli.skipped", message = message))
                        }
                        RunEvent::Progress { .. } => {}
                    },
                )
            })
            .await?;
            println!(
                "{}",
                t!(
                    "cli.backed_up",
                    new = summary.files_new,
                    changed = summary.files_changed,
                    added = summary.data_added,
                    snapshot = summary.snapshot_id.chars().take(8).collect::<String>()
                )
            );
            // the snapshot is there, but the command fails: no older one went (BK-13)
            if summary.empty {
                return Err(t!(
                    "cli.empty",
                    snapshot = summary.snapshot_id.chars().take(8).collect::<String>()
                )
                .to_string());
            }
            if summary.removed > 0 {
                println!("{}", t!("cli.removed", count = summary.removed));
            }
            // the backup is done, the command still fails
            if let Some(error) = summary.expire_failed {
                return Err(t!("cli.expire_failed", error = error).to_string());
            }
        }
        Command::Snapshot(SnapshotCommand::List { repo }) => {
            let id = id_of(&sessions, REPOSITORIES, &repo, "cli.unknown_repository").await?;
            let listed =
                with_password(|given, _| snapshots::snapshots(&sessions, &passwords, id, given))
                    .await?;
            for snapshot in listed.snapshots {
                println!(
                    "{}\t{}\t{}\t{}",
                    snapshot.short_id,
                    snapshot.time,
                    snapshot.hostname,
                    snapshot.paths.join(" ")
                );
            }
        }
        Command::Snapshot(SnapshotCommand::Delete { id, repo }) => {
            let repository =
                id_of(&sessions, REPOSITORIES, &repo, "cli.unknown_repository").await?;
            with_password(|given, _| {
                snapshots::delete(&sessions, &passwords, repository, id.clone(), given)
            })
            .await?;
            println!("{}", t!("cli.snapshot_deleted", id = id, repository = repo));
        }
        Command::Restore { plan, target } => {
            let id = id_of(&sessions, PLANS, &plan, "cli.unknown_plan").await?;
            let shown = target.display().to_string();
            let skipped = with_password(|given, _| {
                restore::restore(&sessions, &passwords, id, target.clone(), given)
            })
            .await?;
            // the others are restored, the command still fails
            for file in &skipped {
                eprintln!("{}", t!("cli.skipped", message = file));
            }
            if !skipped.is_empty() {
                return Err(t!(
                    "cli.restored_partly",
                    plan = plan,
                    target = shown,
                    count = skipped.len()
                )
                .to_string());
            }
            println!("{}", t!("cli.restored", plan = plan, target = shown));
        }
    }
    Ok(())
}

/// calls `call` first with the password of SAK_BACKUP_REPO_PASSWORD or the kept one, then, when it
/// needs one, with one typed on the terminal: twice for a new repository, which `call` initializes
/// with it then
async fn with_password<T, Call>(call: impl Fn(Given, bool) -> Call) -> Result<T, String>
where
    Call: Future<Output = Result<T, RunError>>,
{
    let typed = |password| Given {
        typed: Some(password),
        remember: false,
    };
    match call(Given::default(), false).await {
        Err(RunError::NoPassword) => call(typed(password(false)?), false).await,
        Err(RunError::NeedsInit) => call(typed(password(true)?), true).await,
        result => result,
    }
    .map_err(|error| message(&error))
}

/// an absolute path as restic keeps it in a snapshot: no separator at its end, no `.` or `..` part
/// (BK-11); the server normalizes it the same way
fn normalized(path: &std::path::Path) -> PathBuf {
    use std::path::Component;
    let mut clean = PathBuf::new();
    for component in path.components() {
        match component {
            Component::ParentDir => {
                if clean.parent().is_some() {
                    clean.pop();
                }
            }
            Component::CurDir => {}
            other => clean.push(other),
        }
    }
    clean
}

/// the session of the variables, else the one the window kept (sak-desktop-shared)
async fn session() -> Result<SessionManager, String> {
    sak_desktop_shared::cli::session(IDENTIFIER, ADDRESS_VARIABLE, SESSION_VARIABLE)
        .await
        .map_err(|error| match error {
            SessionError::InvalidAddress(address) => {
                t!("cli.invalid_address", address = address).to_string()
            }
            SessionError::NotSignedIn => t!("cli.not_signed_in").to_string(),
            SessionError::Restore(error) => sak_desktop_shared::window::restore_message(&error),
        })
}

/// a password of a repository typed on the terminal, twice for a new repository
fn password(new: bool) -> Result<String, String> {
    if !std::io::stdin().is_terminal() {
        return Err(t!("cli.no_password").to_string());
    }
    let typed = rpassword::prompt_password(t!("cli.password_prompt").as_ref())
        .map_err(|error| error.to_string())?;
    if new {
        let repeated = rpassword::prompt_password(t!("cli.password_repeat").as_ref())
            .map_err(|error| error.to_string())?;
        if repeated != typed {
            return Err(t!("cli.passwords_differ").to_string());
        }
    }
    if typed.is_empty() {
        Err(t!("cli.no_password").to_string())
    } else {
        Ok(typed)
    }
}

/// the id of the repository or the backup plan of the name
async fn id_of(
    sessions: &SessionManager,
    path: &str,
    name: &str,
    unknown: &str,
) -> Result<u64, String> {
    Ok(by_name(sessions, path, name, unknown).await?["id"]
        .as_u64()
        .unwrap_or_default())
}

async fn by_name(
    sessions: &SessionManager,
    path: &str,
    name: &str,
    unknown: &str,
) -> Result<Value, String> {
    api::by_name(sessions, path, name)
        .await
        .map_err(|error| api_message(&error))?
        .ok_or_else(|| t!(unknown, name = name).to_string())
}

fn text(value: &Value) -> String {
    value.as_str().unwrap_or_default().to_string()
}

fn api_message(error: &ApiError) -> String {
    sak_desktop_shared::cli::api_message(error, &t!("cli.not_signed_in"))
}

fn message(error: &RunError) -> String {
    match error {
        RunError::Api { error } => api_message(error),
        RunError::NoRestic => t!("cli.no_restic").to_string(),
        RunError::WrongPassword
        | RunError::WrongEnvironmentPassword
        | RunError::WrongKeptPassword => t!("cli.wrong_password").to_string(),
        RunError::NoPassword => t!("cli.no_password").to_string(),
        RunError::OtherComputer { host } => t!("cli.other_computer", host = host).to_string(),
        RunError::NoSnapshot => t!("cli.no_snapshot").to_string(),
        RunError::NeedsInitEnvironment => t!("cli.needs_init_environment").to_string(),
        RunError::RepositoryMissing => t!("cli.repository_missing").to_string(),
        RunError::UnknownSnapshot => t!("cli.unknown_snapshot").to_string(),
        // a run of the command line is never cancelled, the only one of its process, and a new
        // repository gets the password typed twice (`with_password`)
        RunError::Cancelled | RunError::Running | RunError::NeedsInit => {
            unreachable!("the command line got {error:?}")
        }
        RunError::Failed { message } => sak_desktop_shared::cli::failed_message(message),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_path_is_normalized_as_restic_keeps_it() {
        for (given, expected) in [
            ("/home/anna/Documents/", "/home/anna/Documents"),
            ("/home//anna/./Documents", "/home/anna/Documents"),
            ("/home/anna/../bert/", "/home/bert"),
            ("/..", "/"),
            ("/", "/"),
        ] {
            assert_eq!(
                normalized(std::path::Path::new(given)),
                PathBuf::from(expected),
                "{given}"
            );
        }
    }

    #[test]
    fn the_identifier_is_the_one_of_the_window() {
        let config: Value = serde_json::from_str(include_str!("../../tauri.conf.json")).unwrap();
        assert_eq!(config["identifier"], IDENTIFIER);
    }
}
