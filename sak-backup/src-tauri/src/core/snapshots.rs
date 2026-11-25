//! the snapshots of a repository, as restic of this computer lists them with the password the user
//! gives (BACKUP-3): the instance keeps them encrypted and can not list them itself

use crate::core::passwords::{Given, PasswordStore, password_of, run_with};
use crate::core::restic::{Restic, error_of};
use crate::core::run::{Run, RunError, Runs, failed};
use sak_desktop_shared::session::SessionManager;
use serde::{Deserialize, Serialize};

/// a snapshot as restic lists it, the newest first
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    pub id: String,
    #[serde(alias = "short_id")]
    pub short_id: String,
    /// when restic took it, with the offset of the computer
    pub time: String,
    pub hostname: String,
    #[serde(default)]
    pub paths: Vec<String>,
    #[serde(default)]
    pub tags: Vec<String>,
    /// what restic 0.17 and later tell of the backup; none of an older restic
    #[serde(default)]
    pub summary: Option<SnapshotSummary>,
}

/// the summary of the backup of a snapshot, as far as the window shows it
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotSummary {
    /// the bytes of the files backed up: what a restore of the snapshot writes
    #[serde(alias = "total_bytes_processed")]
    pub total_bytes_processed: u64,
}

/// the snapshots of a repository; none for one restic has not initialized yet, without asking
/// restic
#[derive(Clone, Debug, Serialize)]
pub struct Snapshots {
    pub initialized: bool,
    pub snapshots: Vec<Snapshot>,
}

/// the snapshots of the repository, the newest first
pub async fn snapshots(
    sessions: &SessionManager,
    passwords: &PasswordStore,
    repository_id: u64,
    given: Given,
) -> Result<Snapshots, RunError> {
    let listed = with_restic(sessions, passwords, repository_id, given, None, list).await?;
    Ok(Snapshots {
        initialized: listed.is_some(),
        snapshots: listed.unwrap_or_default(),
    })
}

/// deletes a snapshot of the repository, and the data no other snapshot needs (`restic forget
/// --prune`)
pub async fn delete(
    sessions: &SessionManager,
    runs: &Runs,
    passwords: &PasswordStore,
    repository_id: u64,
    snapshot_id: String,
    given: Given,
) -> Result<(), RunError> {
    snapshot(&snapshot_id)?;
    // one run at a time, of this process and of every other sakbackup on the repository (DA-33)
    let run = runs.begin()?;
    with_restic(
        sessions,
        passwords,
        repository_id,
        given,
        Some(run),
        move |restic| forget(restic, &snapshot_id),
    )
    .await?
    .ok_or(RunError::UnknownSnapshot)
}

/// runs restic on the repository with the password typed, else the one of SAK_BACKUP_REPO_PASSWORD,
/// else the kept one, which it then keeps or forgets (`run_with`); none for a repository restic has
/// not initialized yet, without asking restic
/// `holding` is the run that changes the repository, which locks it; none for a look at it
async fn with_restic<T: Send + 'static>(
    sessions: &SessionManager,
    passwords: &PasswordStore,
    repository_id: u64,
    given: Given,
    mut holding: Option<Run>,
    run: impl FnOnce(&Restic) -> Result<T, RunError> + Send + 'static,
) -> Result<Option<T>, RunError> {
    let access = crate::core::restic::access(sessions, repository_id).await?;
    if !access.initialized().await? {
        return Ok(None);
    }
    let account = access.account();
    if let Some(holding) = holding.as_mut() {
        holding.lock(&account)?;
    }
    let chosen = password_of(passwords, &account, given.typed)
        .await
        .ok_or(RunError::NoPassword)?;
    let program = crate::core::restic::program().ok_or(RunError::NoRestic)?;
    let repository = access.url();
    run_with(
        passwords,
        &account,
        chosen,
        given.remember,
        move |password| {
            let _holding = holding;
            run(&Restic {
                program: &program,
                repository: &repository,
                password,
            })
        },
    )
    .await
    .map(Some)
}

/// the id of a snapshot, long or short: hexadecimal only, so restic never takes it for an option
pub(crate) fn snapshot(id: &str) -> Result<&str, RunError> {
    if (8..=64).contains(&id.len()) && id.chars().all(|c| c.is_ascii_hexdigit()) {
        Ok(id)
    } else {
        Err(RunError::UnknownSnapshot)
    }
}

/// `restic forget --prune <id>`: the snapshot goes, and the data no other snapshot needs. The id is
/// looked up first: restic retries an unknown one for a minute and then ignores it
pub(crate) fn forget(restic: &Restic, id: &str) -> Result<(), RunError> {
    let id = snapshot(id)?;
    let mut matching = list(restic)?
        .into_iter()
        .filter(|snapshot| snapshot.id.starts_with(id));
    let (Some(found), None) = (matching.next(), matching.next()) else {
        return Err(RunError::UnknownSnapshot);
    };
    let output = restic
        .command()
        .args(["forget", "--prune", &found.id])
        .output()
        .map_err(failed)?;
    let stderr = String::from_utf8_lossy(&output.stderr);
    if output.status.success() && !stderr.contains("Ignoring") {
        return Ok(());
    }
    Err(error_of(&stderr))
}

/// `restic snapshots --json`, the newest first
pub(crate) fn list(restic: &Restic) -> Result<Vec<Snapshot>, RunError> {
    let output = restic
        .command()
        .args(["snapshots", "--json"])
        .output()
        .map_err(failed)?;
    if !output.status.success() {
        return Err(error_of(&String::from_utf8_lossy(&output.stderr)));
    }
    let mut snapshots = parse(&String::from_utf8_lossy(&output.stdout)).map_err(failed)?;
    // the times are rfc 3339, with their offsets: compared as instants
    snapshots.sort_by_key(|snapshot| std::cmp::Reverse(instant(&snapshot.time)));
    Ok(snapshots)
}

fn parse(json: &str) -> serde_json::Result<Vec<Snapshot>> {
    serde_json::from_str(json)
}

/// seconds and nanoseconds since the epoch of an rfc 3339 time, for the order only
fn instant(time: &str) -> Option<(i64, u32)> {
    let parsed = chrono::DateTime::parse_from_rfc3339(time).ok()?;
    Some((parsed.timestamp(), parsed.timestamp_subsec_nanos()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_snapshots_of_restic_are_read() {
        let json = r#"[{"time":"2026-11-21T00:03:49.914206452+01:00","tree":"f5bb","paths":["/home/anna/Documents"],
            "hostname":"annas-laptop","username":"anna","id":"f7dfa4f1aa","short_id":"f7dfa4f1"}]"#;
        assert_eq!(
            parse(json).unwrap(),
            vec![Snapshot {
                id: "f7dfa4f1aa".to_string(),
                short_id: "f7dfa4f1".to_string(),
                time: "2026-11-21T00:03:49.914206452+01:00".to_string(),
                hostname: "annas-laptop".to_string(),
                paths: vec!["/home/anna/Documents".to_string()],
                tags: vec![],
                summary: None,
            }]
        );
        assert_eq!(parse("[]").unwrap(), vec![]);
    }

    #[test]
    fn the_size_of_the_files_comes_with_the_summary_of_restic_0_17() {
        let json = r#"[{"time":"2026-11-21T00:03:49+01:00","paths":["/a"],"hostname":"h","id":"f7dfa4f1aa",
            "short_id":"f7dfa4f1","summary":{"backup_start":"2026-11-21T00:03:40+01:00","files_new":3,
            "total_files_processed":3,"total_bytes_processed":1288490189,"data_added_packed":4096}}]"#;
        let snapshot = &parse(json).unwrap()[0];
        assert_eq!(
            snapshot.summary,
            Some(SnapshotSummary {
                total_bytes_processed: 1_288_490_189
            })
        );
        // to the window in its own names
        assert_eq!(
            serde_json::to_value(snapshot).unwrap()["summary"],
            serde_json::json!({ "totalBytesProcessed": 1_288_490_189u64 })
        );
    }

    #[test]
    fn a_snapshot_id_is_hexadecimal_never_an_option() {
        assert_eq!(snapshot("f7dfa4f1"), Ok("f7dfa4f1"));
        for id in ["--prune", "f7df", "f7dfa4f1 x", "latest"] {
            assert_eq!(snapshot(id), Err(RunError::UnknownSnapshot), "{id}");
        }
    }

    #[test]
    fn the_order_follows_the_instants_whatever_their_offsets() {
        assert!(instant("2026-11-21T00:30:00+01:00") < instant("2026-11-20T23:45:00Z"));
    }
}
