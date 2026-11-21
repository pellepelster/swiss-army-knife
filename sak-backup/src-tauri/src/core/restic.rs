//! restic of this computer and the repositories of the instance it reaches (BACKUP-1, BACKUP-2): a
//! repository is the REST backend of the instance below the origin of the api of the session, with
//! the token of the repository as its login. The token and the password the user gives go to restic
//! in its environment, which only the user reads, never as arguments, which every user of the
//! computer sees; paths go in a file only the user reads

use crate::core::api::REPOSITORIES;
use crate::core::run::{RunError, failed};
use sak_desktop_shared::api;
use sak_desktop_shared::session::SessionManager;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;
use url::Url;

/// restic of this computer: on the path, or where the packages of linux and macOS put it
pub(crate) fn program() -> Option<PathBuf> {
    let name = if cfg!(windows) {
        "restic.exe"
    } else {
        "restic"
    };
    std::env::var_os("PATH")
        .map(|path| std::env::split_paths(&path).collect::<Vec<_>>())
        .unwrap_or_default()
        .into_iter()
        .chain(["/usr/bin", "/usr/local/bin", "/opt/homebrew/bin"].map(PathBuf::from))
        .map(|dir| dir.join(name))
        .find(|candidate| candidate.is_file())
}

/// restic with a repository and its password
pub(crate) struct Restic<'a> {
    pub program: &'a Path,
    /// what restic takes as the repository, with its login
    pub repository: &'a str,
    pub password: &'a str,
}

impl Restic<'_> {
    /// restic with the repository and the password in its environment, and none of the other ways
    /// to give them, which would win over them
    pub fn command(&self) -> Command {
        let mut command = Command::new(self.program);
        for variable in [
            "RESTIC_REPOSITORY_FILE",
            "RESTIC_PASSWORD_FILE",
            "RESTIC_PASSWORD_COMMAND",
        ] {
            command.env_remove(variable);
        }
        command
            .env("RESTIC_REPOSITORY", self.repository)
            .env("RESTIC_PASSWORD", self.password)
            .stdin(Stdio::null());
        command
    }

    /// creates the repository with the password
    pub fn init(&self) -> Result<(), RunError> {
        let output = self.command().arg("init").output().map_err(failed)?;
        if output.status.success() {
            Ok(())
        } else {
            Err(error_of(&String::from_utf8_lossy(&output.stderr)))
        }
    }

    /// restores the snapshot below `target`; restic writes every path below it as the snapshot has
    /// it, so `/` puts them back where they were. The files it could not write, each with why, e.g.
    /// a read-only one it would overwrite; it restored the others
    pub fn restore(&self, snapshot: &str, target: &Path) -> Result<Vec<String>, RunError> {
        let output = self
            .command()
            .args(["restore", snapshot, "--target"])
            .arg(target)
            .output()
            .map_err(failed)?;
        let stderr = String::from_utf8_lossy(&output.stderr);
        let skipped = skipped(&stderr);
        // restic ends with "There were <n> errors" when it skipped files
        if output.status.success() || (!skipped.is_empty() && stderr.contains("There were")) {
            return Ok(skipped);
        }
        if stderr.contains("no snapshot found") || stderr.contains("no matching ID found") {
            return Err(RunError::NoSnapshot);
        }
        Err(error_of(&stderr))
    }
}

/// the backup plan a run of restic is for: its snapshots have its tag, so a run and a restore
/// never take the snapshots of another plan, whatever paths or host name they share (BACKUP-8)
pub(crate) struct Plan<'a> {
    pub id: u64,
    pub host: &'a str,
    pub paths: &'a [String],
}

impl Plan<'_> {
    /// the tag of the snapshots of the plan
    pub fn tag(&self) -> String {
        format!("{PLAN_TAG}{}", self.id)
    }
}

/// what the tag of the snapshots of a backup plan starts with, its id follows
pub(crate) const PLAN_TAG: &str = "sak-plan-";

/// the files a restore skipped, each with why, from what restic wrote to its standard error
fn skipped(stderr: &str) -> Vec<String> {
    stderr
        .lines()
        .filter_map(|line| line.strip_prefix("ignoring error for "))
        .map(String::from)
        .collect()
}

/// how many of the last, daily, weekly, monthly and yearly snapshots of a backup plan a run keeps;
/// none keeps them all (BACKUP-8)
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Keep {
    pub last: Option<u64>,
    pub daily: Option<u64>,
    pub weekly: Option<u64>,
    pub monthly: Option<u64>,
    pub yearly: Option<u64>,
}

impl Keep {
    /// the setting of a backup plan of the api
    pub fn of(plan: &serde_json::Value) -> Keep {
        let number = |name: &str| plan[name].as_u64();
        Keep {
            last: number("keepLast"),
            daily: number("keepDaily"),
            weekly: number("keepWeekly"),
            monthly: number("keepMonthly"),
            yearly: number("keepYearly"),
        }
    }

    /// the options of `restic forget`; none when every snapshot stays
    pub fn options(&self) -> Vec<String> {
        [
            ("--keep-last", self.last),
            ("--keep-daily", self.daily),
            ("--keep-weekly", self.weekly),
            ("--keep-monthly", self.monthly),
            ("--keep-yearly", self.yearly),
        ]
        .into_iter()
        .filter_map(|(option, count)| count.map(|count| format!("{option}={count}")))
        .collect()
    }
}

impl Restic<'_> {
    /// removes the snapshots of the plan `keep` no longer keeps, then the data no snapshot needs;
    /// how many snapshots it removed. Only snapshots with the tag of the plan are looked at, as one
    /// group whatever paths they have: those of other plans, of other computers and the ones
    /// without a tag stay
    pub fn expire(&self, plan: &Plan, keep: &Keep) -> Result<usize, RunError> {
        let options = keep.options();
        if options.is_empty() {
            return Ok(0);
        }
        let output = self
            .command()
            .args(["forget", "--json", "--group-by", "tags", "--tag"])
            .arg(plan.tag())
            .args(options)
            .output()
            .map_err(failed)?;
        if !output.status.success() {
            return Err(error_of(&String::from_utf8_lossy(&output.stderr)));
        }
        let removed = removed(&String::from_utf8_lossy(&output.stdout));
        if removed > 0 {
            let output = self.command().arg("prune").output().map_err(failed)?;
            if !output.status.success() {
                return Err(error_of(&String::from_utf8_lossy(&output.stderr)));
            }
        }
        Ok(removed)
    }
}

/// how many snapshots `restic forget --json` removed, of all its groups
fn removed(json: &str) -> usize {
    serde_json::from_str::<serde_json::Value>(json)
        .ok()
        .and_then(|groups| {
            groups.as_array().map(|groups| {
                groups
                    .iter()
                    .filter_map(|group| group["remove"].as_array())
                    .map(Vec::len)
                    .sum()
            })
        })
        .unwrap_or(0)
}

/// the error restic gave, from what it wrote to its standard error
pub(crate) fn error_of(stderr: &str) -> RunError {
    // only what restic itself says of the password: a path it names may hold the words too
    if stderr
        .lines()
        .any(|line| line.starts_with("Fatal: wrong password"))
    {
        return RunError::WrongPassword;
    }
    let last = stderr
        .lines()
        .rev()
        .find(|line| !line.trim().is_empty())
        .unwrap_or("restic failed")
        .trim_start_matches("Fatal: ");
    failed(last)
}

/// a repository of the user as restic reaches it
pub(crate) struct Access {
    pub id: u64,
    /// its REST backend, without a login
    pub base: Url,
    pub token: String,
}

impl Access {
    /// what the kept password of the repository is named by: the address of its REST backend
    pub fn account(&self) -> String {
        self.base.to_string()
    }

    /// what restic takes as the repository: the REST backend with the token as the password of the
    /// user named like the repository (BACKUP-2)
    pub fn url(&self) -> String {
        let mut url = self.base.clone();
        let _ = url.set_username(&self.id.to_string());
        let _ = url.set_password(Some(&self.token));
        format!("rest:{url}")
    }

    /// whether the repository has its config, so restic initialized it
    pub async fn initialized(&self) -> Result<bool, RunError> {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(failed)?;
        let response = client
            .head(self.base.join("config").map_err(failed)?)
            .basic_auth(self.id, Some(&self.token))
            .send()
            .await
            .map_err(|error| failed(error.without_url()))?;
        match response.status() {
            reqwest::StatusCode::OK => Ok(true),
            reqwest::StatusCode::NOT_FOUND => Ok(false),
            status => Err(failed(status)),
        }
    }
}

/// the repository `id` of the user, with its token from the api
pub(crate) async fn access(sessions: &SessionManager, id: u64) -> Result<Access, RunError> {
    let token = api::call(
        sessions,
        reqwest::Method::GET,
        &format!("{REPOSITORIES}/{id}/token"),
        None,
    )
    .await?;
    Ok(Access {
        id,
        base: base(sessions, id)?,
        token: token["token"].as_str().unwrap_or_default().to_string(),
    })
}

/// the REST backend of the repository `id`, below the api of the session
pub(crate) fn base(sessions: &SessionManager, id: u64) -> Result<Url, RunError> {
    rest_base(&sessions.session_api().unwrap_or_default(), id).ok_or_else(|| failed("no api"))
}

/// the REST backend of a repository: below the origin of the api (SERVER-7)
fn rest_base(api: &str, repository: u64) -> Option<Url> {
    let api = Url::parse(api).ok()?;
    api.join(&format!("/restic/{repository}/")).ok()
}

/// paths, one a line, in a file only the user reads; it goes when it is dropped
pub(crate) struct FilesFrom(PathBuf);

impl FilesFrom {
    pub fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for FilesFrom {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

pub(crate) fn files_from(paths: &[String]) -> std::io::Result<FilesFrom> {
    let mut bytes = [0u8; 8];
    getrandom::fill(&mut bytes).map_err(std::io::Error::other)?;
    let path = std::env::temp_dir().join(format!("sak-backup-{}.paths", u64::from_le_bytes(bytes)));
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&path)?;
    let files = FilesFrom(path);
    for path in paths {
        writeln!(file, "{path}")?;
    }
    file.sync_all()?;
    Ok(files)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_repository_is_below_the_origin_of_the_api_with_the_token_as_its_password() {
        let base = rest_base("https://api.example.org/v1/", 7).unwrap();
        assert_eq!(base.as_str(), "https://api.example.org/restic/7/");
        let access = Access {
            id: 7,
            base,
            token: "tok-en_1".to_string(),
        };
        assert_eq!(
            access.url(),
            "rest:https://7:tok-en_1@api.example.org/restic/7/"
        );
        assert_eq!(access.account(), "https://api.example.org/restic/7/");
        // local:run: the dev server hands /restic on to the backend
        assert_eq!(
            rest_base("http://localhost:4200/api/v1/", 3)
                .unwrap()
                .as_str(),
            "http://localhost:4200/restic/3/"
        );
    }

    #[test]
    fn the_files_a_restore_skipped_are_read() {
        let stderr = "restoring <Snapshot 47159aa1 of [/home/anna/x] by anna@h> to /\n\
            ignoring error for /home/anna/x/.git/objects/ab/cd: open /home/anna/x/.git/objects/ab/cd: permission denied\n\
            Summary: Restored 10 files/dirs (4 B) in 0:00\nFatal: There were 1 errors\n";
        assert_eq!(
            skipped(stderr),
            [
                "/home/anna/x/.git/objects/ab/cd: open /home/anna/x/.git/objects/ab/cd: permission denied"
            ]
        );
        assert!(skipped("Fatal: wrong password").is_empty());
    }

    #[test]
    fn a_keep_setting_is_read_from_the_plan_and_given_to_restic() {
        let plan = serde_json::json!({ "keepDaily": 7, "keepMonthly": 12, "keepLast": null });
        let keep = Keep::of(&plan);
        assert_eq!(keep.options(), ["--keep-daily=7", "--keep-monthly=12"]);
        assert!(Keep::of(&serde_json::json!({})).options().is_empty());
        assert_eq!(
            removed(
                r#"[{"host":"h","keep":[{}],"remove":[{},{}]},{"remove":null},{"remove":[{}]}]"#
            ),
            3
        );
        assert_eq!(removed("not json"), 0);
    }

    #[test]
    fn a_wrong_password_is_told_apart_from_other_errors() {
        assert_eq!(
            error_of("Fatal: wrong password or no key found"),
            RunError::WrongPassword
        );
        // the words in the name of a file are no wrong password
        assert_eq!(
            error_of(
                "ignoring error for /home/anna/wrong password.txt: permission denied\nFatal: There were 1 errors\n"
            ),
            RunError::Failed {
                message: "There were 1 errors".to_string()
            }
        );
        assert_eq!(
            error_of("some line\nFatal: unable to save snapshot: disk full\n"),
            RunError::Failed {
                message: "unable to save snapshot: disk full".to_string()
            }
        );
    }

    #[test]
    fn the_paths_are_in_a_file_only_the_user_reads_which_goes_when_dropped() {
        let files = files_from(&[
            "/home/anna/Documents".to_string(),
            "/home/anna/a b".to_string(),
        ])
        .unwrap();
        let path = files.path().to_path_buf();
        assert_eq!(
            std::fs::read_to_string(&path).unwrap(),
            "/home/anna/Documents\n/home/anna/a b\n"
        );
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
        drop(files);
        assert!(!path.exists());
    }
}
