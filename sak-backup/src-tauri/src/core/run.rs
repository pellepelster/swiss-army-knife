//! runs restic of this computer for a backup plan (BACKUP-3, BACKUP-5) into its repository on the
//! instance (restic.rs); the paths go in a file only the user reads. Nothing of it is kept after
//! the run

use crate::core::api::{PLANS, host};
use crate::core::excludes::Excludes;
use crate::core::passwords::{Given, PasswordStore, Source, password_of, run_with};
use crate::core::restic::{Keep, Plan, Restic, files_from};
use sak_desktop_shared::api::{self, ApiError};
use sak_desktop_shared::session::SessionManager;
use serde::Serialize;
use serde_json::Value;
use std::collections::VecDeque;
use std::io::Read;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Output, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// the run of restic that goes on, one at a time
#[derive(Clone, Default)]
pub struct Runs(Arc<RunState>);

impl Runs {
    /// held while a backup, a restore or a change of the snapshots goes on, restic and what comes
    /// before and after it (the password, the snapshots a plan no longer keeps): sakbackup is not
    /// restarted meanwhile
    pub fn busy(&self) -> Busy {
        self.0.busy.fetch_add(1, Ordering::SeqCst);
        Busy(self.0.clone())
    }

    /// a backup from its start to its end, what it asks the instance and the expiry included: one at
    /// a time, and what `cancel` stops (DA-32)
    pub(crate) fn begin(&self) -> Result<Run, RunError> {
        let child = self.0.child.lock().unwrap();
        if child.is_some() || self.0.starting.swap(true, Ordering::SeqCst) {
            return Err(RunError::Running);
        }
        drop(child);
        self.0.cancelled.store(false, Ordering::SeqCst);
        Ok(Run(self.clone()))
    }

    /// whether a backup, a restore or a change of the snapshots goes on
    pub fn is_running(&self) -> bool {
        self.0.busy.load(Ordering::SeqCst) > 0
            || self.0.starting.load(Ordering::SeqCst)
            || self.0.child.lock().is_ok_and(|child| child.is_some())
    }
}

/// a backup that goes on (`Runs::begin`); it is over when this goes
pub(crate) struct Run(Runs);

impl Run {
    fn cancelled(&self) -> bool {
        self.0.0.cancelled.load(Ordering::SeqCst)
    }

    /// a command of restic in this run that `cancel` stops, e.g. forget or prune of the expiry: its
    /// output, or `Cancelled`
    fn output(&self, command: &mut Command) -> Result<Output, RunError> {
        if self.cancelled() {
            return Err(RunError::Cancelled);
        }
        let mut child = command
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(failed)?;
        let read = |pipe: Option<Box<dyn Read + Send>>| {
            std::thread::spawn(move || {
                let mut bytes = Vec::new();
                if let Some(mut pipe) = pipe {
                    let _ = pipe.read_to_end(&mut bytes);
                }
                bytes
            })
        };
        let stdout = read(
            child
                .stdout
                .take()
                .map(|pipe| Box::new(pipe) as Box<dyn Read + Send>),
        );
        let stderr = read(
            child
                .stderr
                .take()
                .map(|pipe| Box::new(pipe) as Box<dyn Read + Send>),
        );
        let child = Arc::new(Mutex::new(child));
        *self.0.0.child.lock().unwrap() = Some(child.clone());
        // a cancel that came while it started
        if self.cancelled() {
            let _ = child.lock().unwrap().kill();
        }
        let status = loop {
            if let Some(status) = child.lock().unwrap().try_wait().map_err(failed)? {
                break status;
            }
            std::thread::sleep(Duration::from_millis(50));
        };
        *self.0.0.child.lock().unwrap() = None;
        let (stdout, stderr) = (
            stdout.join().unwrap_or_default(),
            stderr.join().unwrap_or_default(),
        );
        if self.cancelled() {
            return Err(RunError::Cancelled);
        }
        Ok(Output {
            status,
            stdout,
            stderr,
        })
    }
}

impl Drop for Run {
    fn drop(&mut self) {
        self.0.0.starting.store(false, Ordering::SeqCst);
        *self.0.0.child.lock().unwrap() = None;
    }
}

/// what `Runs::busy` hands out; the work is over when it goes
pub struct Busy(Arc<RunState>);

impl Drop for Busy {
    fn drop(&mut self) {
        self.0.busy.fetch_sub(1, Ordering::SeqCst);
    }
}

#[derive(Default)]
struct RunState {
    child: Mutex<Option<Arc<Mutex<Child>>>>,
    // a backup that goes on (`Run`), from its start to its end, also while no restic runs
    starting: AtomicBool,
    cancelled: AtomicBool,
    // the commands that hold a `Busy`
    busy: std::sync::atomic::AtomicUsize,
}

/// what a run tells the page while it goes on
#[derive(Clone, Debug, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum RunEvent {
    /// restic initializes the repository, on its first run
    Initializing,
    /// restic backs up: how far it is
    Progress {
        percent: f64,
        files_done: u64,
        total_files: u64,
        bytes_done: u64,
        total_bytes: u64,
        /// the bytes restic read and checked a second, over the last seconds; none before a
        /// second went by. Not what it uploads: a file it already has counts in full
        bytes_per_second: Option<u64>,
        /// the time left as restic guesses it, none before it knows
        seconds_remaining: Option<u64>,
        /// a file restic reads now, none between files
        current_file: Option<String>,
    },
    /// a path restic could not read, e.g. one that is gone; the run goes on
    Skipped { message: String },
    /// restic removes the snapshots the plan no longer keeps (BACKUP-8)
    Expiring,
}

/// what a run that ended added, as restic sums it up
#[derive(Clone, Debug, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RunSummary {
    pub files_new: u64,
    pub files_changed: u64,
    pub files_unmodified: u64,
    pub data_added: u64,
    pub snapshot_id: String,
    /// the snapshot has no file, e.g. a disk that is not mounted: no older snapshot was removed (BK-13)
    pub empty: bool,
    /// the older snapshots of the plan removed after it (BACKUP-8)
    pub removed: usize,
    /// why they could not be removed; the backup itself is done. The window and the command line
    /// say it in their words, as for a run that failed
    pub expire_failed: Option<RunError>,
}

/// why a run did not end with a snapshot
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum RunError {
    /// the repository has no backup yet: the page asks for its password twice and runs again with init
    NeedsInit,
    /// the repository has no backup yet and SAK_BACKUP_REPO_PASSWORD is set: the page asks before it
    /// runs again with init and that password (BK-6)
    NeedsInitEnvironment,
    /// the instance says the repository has no config, but its password is kept, so it had one: it is
    /// not initialized again, e.g. while the data volume of the instance is not there (BK-6)
    RepositoryMissing,
    /// restic is not installed on this computer
    NoRestic,
    /// the password is not the one of the repository
    WrongPassword,
    /// the one of SAK_BACKUP_REPO_PASSWORD is not the one of the repository
    WrongEnvironmentPassword,
    /// restic refused the kept one; it stays kept
    WrongKeptPassword,
    /// no password was given
    NoPassword,
    /// the backup is one of another computer (BACKUP-5)
    OtherComputer {
        host: String,
    },
    /// another run goes on
    Running,
    /// the repository has no snapshot of the backup plan to restore, or none at all
    NoSnapshot,
    /// the repository has no snapshot of the id
    UnknownSnapshot,
    Cancelled,
    /// the instance refused or did not answer
    Api {
        error: ApiError,
    },
    /// restic failed: the last thing it said
    Failed {
        message: String,
    },
}

impl From<ApiError> for RunError {
    fn from(error: ApiError) -> Self {
        RunError::Api { error }
    }
}

/// what restic needs for a backup plan of this computer: the program, the repository with its login,
/// the host and the paths, and whether the repository has no backup yet
pub(crate) struct Prepared {
    pub id: u64,
    pub program: PathBuf,
    pub repository: String,
    pub host: String,
    pub paths: Vec<String>,
    pub first: bool,
    /// the address of the REST backend of its repository, without a login: its kept password
    pub account: String,
    /// what a run keeps of the snapshots of the plan (BACKUP-8)
    pub keep: Keep,
    /// what a run leaves out
    pub excludes: Excludes,
}

impl Prepared {
    /// the plan as restic takes it
    pub fn plan(&self) -> Plan<'_> {
        Plan {
            id: self.id,
            host: &self.host,
            paths: &self.paths,
            excludes: &self.excludes,
        }
    }

    /// restic with the repository of the plan and `password`
    pub fn restic<'a>(&'a self, password: &'a str) -> Restic<'a> {
        Restic {
            program: &self.program,
            repository: &self.repository,
            password,
        }
    }
}

/// the backup plan `id` of this computer, ready for restic; only one of this computer (BACKUP-5)
pub(crate) async fn prepare(sessions: &SessionManager, id: u64) -> Result<Prepared, RunError> {
    let plan = api::call(
        sessions,
        reqwest::Method::GET,
        &format!("{PLANS}/{id}"),
        None,
    )
    .await?;
    let host = host();
    if plan["host"].as_str() != Some(host.as_str()) {
        return Err(RunError::OtherComputer {
            host: plan["host"].as_str().unwrap_or_default().to_string(),
        });
    }
    let repository = plan["repositoryId"]
        .as_u64()
        .ok_or_else(|| failed("the SAK instance answered a backup plan without a repository"))?;
    let access = crate::core::restic::access(sessions, repository).await?;
    let paths = plan["paths"]
        .as_array()
        .map(|paths| {
            paths
                .iter()
                .filter_map(Value::as_str)
                .map(String::from)
                .collect()
        })
        .unwrap_or_default();
    let program = crate::core::restic::program().ok_or(RunError::NoRestic)?;
    let first = !access.initialized().await?;
    Ok(Prepared {
        id,
        program,
        repository: access.url(),
        host,
        paths,
        first,
        account: access.account(),
        keep: Keep::of(&plan),
        excludes: Excludes::of(&plan),
    })
}

/// backs up the backup plan `id` of this computer with restic, into its repository; `init` creates
/// the repository with the password first, when it has no backup yet. A new repository without
/// `init` is `NeedsInit`: its password is asked for twice
pub async fn back_up(
    sessions: &SessionManager,
    runs: &Runs,
    passwords: &PasswordStore,
    id: u64,
    given: Given,
    init: bool,
    on_event: impl Fn(RunEvent) + Send + Sync + Clone + 'static,
) -> Result<RunSummary, RunError> {
    // one run from here: a cancel while the instance is asked or the password is looked up stops it
    // before restic, one during the expiry stops forget or prune
    let run = runs.begin()?;
    let prepared = prepare(sessions, id).await?;
    let account = prepared.account.clone();
    let Some(chosen) = password_of(passwords, &account, given.typed).await else {
        return Err(if prepared.first {
            RunError::NeedsInit
        } else {
            RunError::NoPassword
        });
    };
    may_init(prepared.first, chosen.1, init)?;
    if run.cancelled() {
        return Err(RunError::Cancelled);
    }
    run_with(
        passwords,
        &account,
        chosen,
        given.remember,
        move |password| {
            let restic = prepared.restic(password);
            let mut summary = backup(
                &run,
                &restic,
                &prepared.plan(),
                prepared.first,
                on_event.clone(),
            )?;
            // the backup is done; the snapshots the plan no longer keeps go after it (BACKUP-8), but
            // not after an empty one: it would push out the last ones with data (BK-13). A cancel
            // stops forget or prune, the snapshot stays
            if !summary.empty && !prepared.keep.options().is_empty() {
                on_event(RunEvent::Expiring);
                match restic.expire(&prepared.plan(), &prepared.keep, &|command| {
                    run.output(command)
                }) {
                    Ok(removed) => summary.removed = removed,
                    Err(error) => summary.expire_failed = Some(error),
                }
            }
            Ok(summary)
        },
    )
    .await
}

/// whether a run may go on: a new repository (`first`) is initialized only when asked for (`init`),
/// never with a kept password, which proves the repository had a config (BK-6)
fn may_init(first: bool, source: Source, init: bool) -> Result<(), RunError> {
    if !first {
        return Ok(());
    }
    match source {
        Source::Kept => Err(RunError::RepositoryMissing),
        Source::Environment if !init => Err(RunError::NeedsInitEnvironment),
        Source::Typed if !init => Err(RunError::NeedsInit),
        _ => Ok(()),
    }
}

/// runs restic for one backup plan, initializing the repository first when `init`; one run at a
/// time, `cancel_backup` stops it. Blocks until restic ended
#[cfg(test)]
pub(crate) fn run_restic(
    runs: &Runs,
    restic: &Restic,
    plan: &Plan,
    init: bool,
    on_event: impl Fn(RunEvent) + Send + Sync + Clone + 'static,
) -> Result<RunSummary, RunError> {
    backup(&runs.begin()?, restic, plan, init, on_event)
}

/// restic of the backup in `run`, initializing the repository first when `init`
fn backup(
    run: &Run,
    restic: &Restic,
    plan: &Plan,
    init: bool,
    on_event: impl Fn(RunEvent) + Send + Sync + Clone + 'static,
) -> Result<RunSummary, RunError> {
    let runs = &run.0;
    let result = run_restic_now(runs, restic, plan, init, on_event);
    *runs.0.child.lock().unwrap() = None;
    if run.cancelled() {
        return Err(RunError::Cancelled);
    }
    result
}

fn run_restic_now(
    runs: &Runs,
    restic: &Restic,
    plan: &Plan,
    init: bool,
    on_event: impl Fn(RunEvent) + Send + Sync + Clone + 'static,
) -> Result<RunSummary, RunError> {
    if init {
        on_event(RunEvent::Initializing);
        restic.init()?;
    }
    let files = files_from(plan.paths).map_err(failed)?;
    let exclude_file = crate::core::restic::exclude_file(plan.excludes).map_err(failed)?;
    let mut command = restic.command();
    command
        .args(["backup", "--json", "--host", plan.host, "--tag"])
        .arg(plan.tag())
        .arg("--files-from-verbatim")
        .arg(files.path())
        .args(
            plan.excludes
                .arguments(exclude_file.as_ref().map(|file| file.path())),
        )
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = command.spawn().map_err(failed)?;
    let (stdout, stderr) = (child.stdout.take().unwrap(), child.stderr.take().unwrap());
    let child = Arc::new(Mutex::new(child));
    *runs.0.child.lock().unwrap() = Some(child.clone());
    // a cancel that came while restic started
    if runs.0.cancelled.load(Ordering::SeqCst) {
        let _ = child.lock().unwrap().kill();
    }

    let errors = std::thread::spawn({
        let on_event = on_event.clone();
        move || {
            let mut lines = Vec::new();
            for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                if line.contains("skipping") || line.starts_with("error:") {
                    on_event(RunEvent::Skipped {
                        message: line.clone(),
                    });
                }
                lines.push(line);
            }
            lines
        }
    });
    let mut summary = None;
    let mut rate = Rate::default();
    for line in BufReader::new(stdout).lines().map_while(Result::ok) {
        match parse(&line) {
            Some(Parsed::Progress(mut event)) => {
                if let RunEvent::Progress {
                    bytes_done,
                    bytes_per_second,
                    ..
                } = &mut event
                {
                    *bytes_per_second = rate.of(Instant::now(), *bytes_done);
                }
                on_event(event)
            }
            Some(Parsed::Summary(done)) => summary = Some(done),
            None => {}
        }
    }
    let status = loop {
        if let Some(status) = child.lock().unwrap().try_wait().map_err(failed)? {
            break status;
        }
        std::thread::sleep(Duration::from_millis(50));
    };
    let errors = errors.join().unwrap_or_default();
    drop(files);
    // 3: a snapshot, but some paths could not be read (restic 0.17 and later)
    match (status.code(), summary) {
        (Some(0 | 3), Some(summary)) => Ok(summary),
        _ => Err(crate::core::restic::error_of(&errors.join("\n"))),
    }
}

/// stops the run that goes on, also before restic started and during the expiry
pub fn cancel(runs: &Runs) {
    if runs.0.starting.load(Ordering::SeqCst) {
        runs.0.cancelled.store(true, Ordering::SeqCst);
    }
    if let Some(child) = runs.0.child.lock().unwrap().as_ref() {
        let _ = child.lock().unwrap().kill();
    }
}

pub(crate) fn failed(error: impl std::fmt::Display) -> RunError {
    RunError::Failed {
        message: error.to_string(),
    }
}

/// how fast `bytes_done` grows, over the last seconds: steady enough to read, and still the speed of
/// now and not of the whole run
#[derive(Default)]
struct Rate(VecDeque<(Instant, u64)>);

impl Rate {
    const WINDOW: Duration = Duration::from_secs(5);

    /// the bytes a second at `now`, with `bytes` done; none before a second of them
    fn of(&mut self, now: Instant, bytes: u64) -> Option<u64> {
        self.0.push_back((now, bytes));
        // the oldest kept is the last one at least a window back, so the window is full
        while self.0.len() > 2 && now.duration_since(self.0[1].0) >= Self::WINDOW {
            self.0.pop_front();
        }
        let (since, from) = *self.0.front()?;
        let elapsed = now.duration_since(since).as_secs_f64();
        (elapsed >= 1.0).then(|| (bytes.saturating_sub(from) as f64 / elapsed) as u64)
    }
}

enum Parsed {
    Progress(RunEvent),
    Summary(RunSummary),
}

/// a line of `restic backup --json`: its status or its summary
fn parse(line: &str) -> Option<Parsed> {
    let value: Value = serde_json::from_str(line).ok()?;
    let number = |name: &str| value[name].as_u64().unwrap_or(0);
    match value["message_type"].as_str()? {
        "status" => Some(Parsed::Progress(RunEvent::Progress {
            percent: value["percent_done"].as_f64().unwrap_or(0.0),
            files_done: number("files_done"),
            total_files: number("total_files"),
            bytes_done: number("bytes_done"),
            total_bytes: number("total_bytes"),
            bytes_per_second: None,
            seconds_remaining: value["seconds_remaining"].as_u64(),
            current_file: value["current_files"][0].as_str().map(str::to_string),
        })),
        "summary" => Some(Parsed::Summary(RunSummary {
            files_new: number("files_new"),
            files_changed: number("files_changed"),
            files_unmodified: number("files_unmodified"),
            data_added: number("data_added"),
            empty: number("total_files_processed") == 0,
            snapshot_id: value["snapshot_id"]
                .as_str()
                .unwrap_or_default()
                .to_string(),
            ..RunSummary::default()
        })),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sakbackup_is_busy_while_a_command_holds_it_or_a_run_starts() {
        let runs = Runs::default();
        assert!(!runs.is_running());
        let backup = runs.busy();
        let restore = runs.busy();
        assert!(runs.is_running());
        drop(backup);
        assert!(runs.is_running());
        drop(restore);
        assert!(!runs.is_running());
        runs.0.starting.store(true, Ordering::SeqCst);
        assert!(runs.is_running());
    }

    #[test]
    fn a_new_repository_is_initialized_only_when_asked_never_with_a_kept_password() {
        for source in [Source::Typed, Source::Environment, Source::Kept] {
            for init in [false, true] {
                assert_eq!(may_init(false, source, init), Ok(()));
            }
        }
        assert_eq!(
            may_init(true, Source::Typed, false),
            Err(RunError::NeedsInit)
        );
        assert_eq!(may_init(true, Source::Typed, true), Ok(()));
        assert_eq!(
            may_init(true, Source::Environment, false),
            Err(RunError::NeedsInitEnvironment)
        );
        assert_eq!(may_init(true, Source::Environment, true), Ok(()));
        for init in [false, true] {
            assert_eq!(
                may_init(true, Source::Kept, init),
                Err(RunError::RepositoryMissing)
            );
        }
    }

    #[test]
    fn the_rate_is_the_bytes_a_second_over_the_last_seconds() {
        let start = Instant::now();
        let at = |seconds: f64| start + Duration::from_secs_f64(seconds);
        let mut rate = Rate::default();
        // nothing before a second went by
        assert_eq!(rate.of(at(0.0), 0), None);
        assert_eq!(rate.of(at(0.5), 50), None);
        assert_eq!(rate.of(at(1.0), 100), Some(100));
        assert_eq!(rate.of(at(2.0), 300), Some(150));
        // a run that slows down: after the window only the slow seconds count
        for second in 3..=10 {
            rate.of(at(second as f64), 300 + (second - 2) * 10);
        }
        assert_eq!(rate.of(at(11.0), 390), Some(10));
    }

    #[test]
    fn the_progress_and_the_summary_of_restic_are_read() {
        let status = r#"{"message_type":"status","percent_done":0.5,"total_files":2,"files_done":1,"total_bytes":300,"bytes_done":150,"seconds_remaining":42,"current_files":["/home/anna/a.txt","/home/anna/b.txt"]}"#;
        match parse(status) {
            Some(Parsed::Progress(RunEvent::Progress {
                percent,
                files_done,
                total_bytes,
                seconds_remaining,
                current_file,
                ..
            })) => {
                assert_eq!(
                    (percent, files_done, total_bytes, seconds_remaining),
                    (0.5, 1, 300, Some(42))
                );
                assert_eq!(current_file.as_deref(), Some("/home/anna/a.txt"));
            }
            _ => panic!("no progress"),
        }
        let summary = r#"{"message_type":"summary","files_new":2,"files_changed":1,"files_unmodified":4,"total_files_processed":7,"data_added":3003594,"snapshot_id":"f7df"}"#;
        match parse(summary) {
            Some(Parsed::Summary(done)) => assert_eq!(
                done,
                RunSummary {
                    files_new: 2,
                    files_changed: 1,
                    files_unmodified: 4,
                    data_added: 3003594,
                    snapshot_id: "f7df".to_string(),
                    ..RunSummary::default()
                }
            ),
            _ => panic!("no summary"),
        }
        // a snapshot without a file, e.g. of a disk that is not mounted (BK-13)
        let empty = r#"{"message_type":"summary","files_new":0,"total_files_processed":0,"snapshot_id":"a1"}"#;
        assert!(matches!(
            parse(empty),
            Some(Parsed::Summary(RunSummary { empty: true, .. }))
        ));
        assert!(parse("not json").is_none());
        assert!(parse(r#"{"message_type":"verbose_status"}"#).is_none());
    }
}

// with the restic of the machine (TEST-2), on a local repository: what the REST backend adds is in
// the integration test of the backend (ResticRestIntegrationTest)
#[cfg(test)]
mod integration {
    use super::*;

    fn dir(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("sak-backup-run-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn restic_initializes_the_repository_backs_up_and_refuses_a_wrong_password() {
        let program = crate::core::restic::program().expect("restic on the path");
        let work = dir("backup");
        let source = work.join("documents");
        std::fs::create_dir_all(&source).unwrap();
        std::fs::write(source.join("letter.txt"), "Dear restic").unwrap();
        let repository = work.join("repository").to_string_lossy().into_owned();
        let paths = vec![
            source.to_string_lossy().into_owned(),
            work.join("gone").to_string_lossy().into_owned(),
        ];
        let runs = Runs::default();
        let events = Arc::new(Mutex::new(Vec::new()));
        let seen = events.clone();
        let on_event = move |event: RunEvent| seen.lock().unwrap().push(event);
        let restic = |password| Restic {
            program: &program,
            repository: &repository,
            password,
        };
        let plan = Plan {
            id: 7,
            host: "annas-laptop",
            paths: &paths,
            excludes: &crate::core::excludes::NO_EXCLUDES,
        };

        let first =
            run_restic(&runs, &restic("a password"), &plan, true, on_event.clone()).unwrap();
        assert_eq!((first.files_new, first.files_changed), (1, 0));
        assert!(first.data_added > 0);
        assert_eq!(first.snapshot_id.len(), 64);
        {
            let events = events.lock().unwrap();
            assert!(matches!(events.first(), Some(RunEvent::Initializing)));
            assert!(events.iter().any(
                |event| matches!(event, RunEvent::Skipped { message } if message.contains("gone"))
            ));
        }

        std::fs::write(source.join("letter.txt"), "Dear restic, again").unwrap();
        let second =
            run_restic(&runs, &restic("a password"), &plan, false, on_event.clone()).unwrap();
        assert_eq!((second.files_new, second.files_changed), (0, 1));

        assert_eq!(
            run_restic(&runs, &restic("wrong"), &plan, false, on_event.clone()),
            Err(RunError::WrongPassword)
        );
        // the snapshots, the newest first, with the host and the paths
        let snapshots = crate::core::snapshots::list(&restic("a password")).unwrap();
        assert_eq!(snapshots.len(), 2);
        assert_eq!(snapshots[0].id, second.snapshot_id);
        assert_eq!(snapshots[1].id, first.snapshot_id);
        assert_eq!(snapshots[0].hostname, "annas-laptop");
        // each has the tag of its plan
        assert_eq!(snapshots[0].tags, ["sak-plan-7"]);
        assert_eq!(
            snapshots[0].paths,
            vec![source.to_string_lossy().into_owned()]
        );
        assert_eq!(
            crate::core::snapshots::list(&restic("wrong")),
            Err(RunError::WrongPassword)
        );
        // a snapshot deleted, the other stays; an unknown one is told apart
        crate::core::snapshots::forget(&restic("a password"), &first.snapshot_id).unwrap();
        let left = crate::core::snapshots::list(&restic("a password")).unwrap();
        assert_eq!(
            left.iter().map(|snapshot| &snapshot.id).collect::<Vec<_>>(),
            [&second.snapshot_id]
        );
        assert_eq!(
            crate::core::snapshots::forget(&restic("a password"), &first.snapshot_id),
            Err(RunError::UnknownSnapshot)
        );
        // that the file of the paths goes is the_paths_are_in_a_file_only_the_user_reads_which_goes_when_dropped
        std::fs::remove_dir_all(&work).unwrap();
    }

    // BK-1, BK-2 of the review: plans that share a path, one with the same paths, and a snapshot made
    // by hand
    #[test]
    fn a_run_expires_and_a_restore_takes_only_the_snapshots_of_its_plan() {
        let program = crate::core::restic::program().expect("restic on the path");
        let work = dir("plans");
        let (a, b) = (work.join("a"), work.join("b"));
        for folder in [&a, &b] {
            std::fs::create_dir_all(folder).unwrap();
            std::fs::write(folder.join("file.txt"), "0").unwrap();
        }
        let repository = work.join("repository").to_string_lossy().into_owned();
        let restic = Restic {
            program: &program,
            repository: &repository,
            password: "a password",
        };
        let path = |folder: &PathBuf| folder.to_string_lossy().into_owned();
        let (only_a, a_and_b) = (vec![path(&a)], vec![path(&a), path(&b)]);
        let plan = |id, paths| Plan {
            id,
            host: "annas-laptop",
            paths,
            excludes: &crate::core::excludes::NO_EXCLUDES,
        };
        let (documents, everything, twin) = (plan(1, &only_a), plan(2, &a_and_b), plan(3, &only_a));
        let runs = Runs::default();
        restic.init().unwrap();
        let mut newest = String::new();
        for round in 1..=3 {
            std::fs::write(a.join("file.txt"), round.to_string()).unwrap();
            newest = run_restic(&runs, &restic, &documents, false, |_| {})
                .unwrap()
                .snapshot_id;
            // the others are newer than the newest of the plan
            for other in [&everything, &twin] {
                run_restic(&runs, &restic, other, false, |_| {}).unwrap();
            }
        }
        // one made by hand, of the computer and the path, without a tag
        let by_hand = restic
            .command()
            .args(["backup", "--host", "annas-laptop"])
            .arg(&a)
            .output()
            .unwrap();
        assert!(by_hand.status.success());

        let keep = Keep {
            last: Some(1),
            ..Keep::default()
        };
        assert_eq!(
            restic.expire(&documents, &keep, &|command| command
                .output()
                .map_err(failed)),
            Ok(2)
        );
        let snapshots = crate::core::snapshots::list(&restic).unwrap();
        let of = |tag: &str| {
            snapshots
                .iter()
                .filter(|snapshot| snapshot.tags.iter().any(|each| each == tag))
                .count()
        };
        assert_eq!(
            (of("sak-plan-1"), of("sak-plan-2"), of("sak-plan-3")),
            (1, 3, 3)
        );
        assert_eq!(
            snapshots
                .iter()
                .filter(|snapshot| snapshot.tags.is_empty())
                .count(),
            1
        );
        // a second time there is nothing to remove
        assert_eq!(
            restic.expire(&documents, &keep, &|command| command
                .output()
                .map_err(failed)),
            Ok(0)
        );

        // a restore takes the newest of the plan, though the others are newer and have its path
        let chosen = crate::core::restore::newest_of(&snapshots, &documents).unwrap();
        assert_eq!(chosen.id, newest);
        let target = work.join("restored");
        assert_eq!(restic.restore(&chosen.id, &target), Ok(vec![]));
        let below = |folder: &PathBuf| target.join(folder.strip_prefix("/").unwrap());
        assert_eq!(
            std::fs::read_to_string(below(&a).join("file.txt")).unwrap(),
            "3"
        );
        assert!(!below(&b).exists());
        std::fs::remove_dir_all(&work).unwrap();
    }

    #[test]
    fn a_cancelled_run_leaves_a_repository_that_the_next_run_and_a_check_take() {
        let program = crate::core::restic::program().expect("restic on the path");
        let work = dir("cancel");
        let source = work.join("documents");
        std::fs::create_dir_all(&source).unwrap();
        // random, so restic needs a while for it
        let mut random = vec![0u8; 256 * 1024 * 1024];
        getrandom::fill(&mut random).unwrap();
        std::fs::write(source.join("big.bin"), &random).unwrap();
        drop(random);
        let repository = work.join("repository").to_string_lossy().into_owned();
        let paths = vec![source.to_string_lossy().into_owned()];
        let restic = Restic {
            program: &program,
            repository: &repository,
            password: "a password",
        };
        let plan = Plan {
            id: 3,
            host: "annas-laptop",
            paths: &paths,
            excludes: &crate::core::excludes::NO_EXCLUDES,
        };
        let path_files = || -> std::collections::BTreeSet<PathBuf> {
            std::fs::read_dir(std::env::temp_dir())
                .unwrap()
                .filter_map(|entry| entry.ok().map(|entry| entry.path()))
                .filter(|path| {
                    let name = path.file_name().unwrap().to_string_lossy();
                    name.starts_with("sak-backup-") && name.ends_with(".paths")
                })
                .collect()
        };
        let before = path_files();

        let runs = Runs::default();
        // cancelled once restic runs
        let canceller = std::thread::spawn({
            let runs = runs.clone();
            move || {
                while runs.0.child.lock().unwrap().is_none() {
                    std::thread::sleep(Duration::from_millis(10));
                }
                std::thread::sleep(Duration::from_millis(300));
                cancel(&runs);
            }
        });
        assert_eq!(
            run_restic(&runs, &restic, &plan, true, |_| {}),
            Err(RunError::Cancelled)
        );
        canceller.join().unwrap();
        // the file of the paths is gone with its run; another test may have one of its own meanwhile
        let left: Vec<_> = path_files()
            .difference(&before)
            .filter(|path| path.exists())
            .cloned()
            .collect();
        assert!(
            left.iter().all(|path| !std::fs::read_to_string(path)
                .unwrap_or_default()
                .contains(&paths[0])),
            "{left:?}"
        );

        // the next run backs up all of it, and restic finds the repository fine
        let summary = run_restic(&runs, &restic, &plan, false, |_| {}).unwrap();
        assert_eq!(summary.snapshot_id.len(), 64);
        let check = restic.command().arg("check").output().unwrap();
        assert!(
            check.status.success(),
            "{}",
            String::from_utf8_lossy(&check.stderr)
        );
        let _ = std::fs::remove_dir_all(&work);
    }

    #[test]
    fn a_cancel_before_restic_is_seen_and_the_run_is_one_until_it_ends() {
        let runs = Runs::default();
        let run = runs.begin().unwrap();
        // the instance is asked, no restic runs yet: a second one is refused, a cancel counts
        assert!(runs.is_running());
        assert!(matches!(runs.begin(), Err(RunError::Running)));
        cancel(&runs);
        assert!(run.cancelled());
        assert_eq!(
            run.output(&mut Command::new("true")).unwrap_err(),
            RunError::Cancelled
        );
        drop(run);
        assert!(!runs.is_running());
        // the next run begins without the cancel of the last one
        assert!(!runs.begin().unwrap().cancelled());
    }

    #[cfg(unix)]
    #[test]
    fn a_cancel_stops_a_command_of_the_expiry() {
        let runs = Runs::default();
        let run = runs.begin().unwrap();
        let canceller = std::thread::spawn({
            let runs = runs.clone();
            move || {
                while runs.0.child.lock().unwrap().is_none() {
                    std::thread::sleep(Duration::from_millis(10));
                }
                cancel(&runs);
            }
        });
        let started = Instant::now();
        let mut command = Command::new("sleep");
        command.arg("30");
        assert_eq!(run.output(&mut command).unwrap_err(), RunError::Cancelled);
        assert!(started.elapsed() < Duration::from_secs(10));
        canceller.join().unwrap();
        assert!(runs.0.child.lock().unwrap().is_none());
        // without a cancel it gives the output of the command
        let runs = Runs::default();
        let run = runs.begin().unwrap();
        let mut command = Command::new("echo");
        command.arg("forgotten");
        let output = run.output(&mut command).unwrap();
        assert!(output.status.success());
        assert_eq!(String::from_utf8_lossy(&output.stdout), "forgotten\n");
    }

    #[test]
    fn one_run_at_a_time() {
        let runs = Runs::default();
        runs.0.starting.store(true, Ordering::SeqCst);
        assert_eq!(
            run_restic(
                &runs,
                &Restic {
                    program: std::path::Path::new("restic"),
                    repository: "/nowhere",
                    password: "p",
                },
                &Plan {
                    id: 1,
                    host: "h",
                    paths: &[],
                    excludes: &crate::core::excludes::NO_EXCLUDES,
                },
                false,
                |_| {}
            ),
            Err(RunError::Running)
        );
    }
}
