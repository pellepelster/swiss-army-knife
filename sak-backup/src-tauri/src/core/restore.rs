//! restores the newest snapshot of a backup plan of this computer (BACKUP-3, BACKUP-5, BACKUP-8) with restic,
//! where its paths were or below another folder, with the password given as for a run: typed, from
//! SAK_BACKUP_REPO_PASSWORD or kept. restic writes the files of the snapshot back, a changed one with
//! the content of the snapshot; a file added since stays

use crate::core::passwords::{Given, PasswordStore, password_of, run_with};
use crate::core::restic::{PLAN_TAG, Plan};
use crate::core::run::{RunError, failed, prepare};
use crate::core::snapshots::{Snapshot, list};
use sak_desktop_shared::session::SessionManager;
use std::path::PathBuf;

/// restores the backup plan `id` below `target`, an absolute folder; `/` puts the files back where
/// they were. The files restic could not write, each with why; it restored the others
pub async fn restore(
    sessions: &SessionManager,
    passwords: &PasswordStore,
    id: u64,
    target: PathBuf,
    given: Given,
) -> Result<Vec<String>, RunError> {
    if !target.is_absolute() {
        return Err(failed(target.display()));
    }
    let prepared = prepare(sessions, id).await?;
    if prepared.first {
        return Err(RunError::NoSnapshot);
    }
    let account = prepared.account.clone();
    let chosen = password_of(passwords, &account, given.typed)
        .await
        .ok_or(RunError::NoPassword)?;
    run_with(
        passwords,
        &account,
        chosen,
        given.remember,
        move |password| {
            let restic = prepared.restic(password);
            let snapshots = list(&restic)?;
            let snapshot = newest_of(&snapshots, &prepared.plan()).ok_or(RunError::NoSnapshot)?;
            restic.restore(&snapshot.id, &target)
        },
    )
    .await
}

/// the snapshot a restore of the plan takes, of `snapshots` with the newest first: the newest one
/// with the tag of the plan; without one, the newest one of this computer and exactly the paths of
/// the plan that has the tag of no plan, made before the snapshots had tags. Never a snapshot of
/// another plan, whatever paths it shares (BACKUP-8)
pub(crate) fn newest_of<'a>(snapshots: &'a [Snapshot], plan: &Plan) -> Option<&'a Snapshot> {
    let tag = plan.tag();
    let mut paths: Vec<&str> = plan.paths.iter().map(String::as_str).collect();
    paths.sort_unstable();
    let before_tags = |snapshot: &&Snapshot| {
        let mut of_snapshot: Vec<&str> = snapshot.paths.iter().map(String::as_str).collect();
        of_snapshot.sort_unstable();
        snapshot.hostname == plan.host
            && of_snapshot == paths
            && !snapshot.tags.iter().any(|tag| tag.starts_with(PLAN_TAG))
    };
    snapshots
        .iter()
        .find(|snapshot| snapshot.tags.contains(&tag))
        .or_else(|| snapshots.iter().find(before_tags))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snapshot(id: &str, host: &str, paths: &[&str], tags: &[&str]) -> Snapshot {
        Snapshot {
            id: id.to_string(),
            short_id: id.to_string(),
            time: String::new(),
            hostname: host.to_string(),
            paths: paths.iter().map(|path| path.to_string()).collect(),
            tags: tags.iter().map(|tag| tag.to_string()).collect(),
        }
    }

    #[test]
    fn a_restore_takes_the_newest_snapshot_of_its_plan_never_one_of_another() {
        let paths = vec!["/a".to_string()];
        let plan = Plan {
            id: 7,
            host: "laptop",
            paths: &paths,
        };
        // the newest first, as restic is listed
        let snapshots = [
            // another plan with the path and more, and one with the same paths
            snapshot("b2", "laptop", &["/a", "/b"], &["sak-plan-8"]),
            snapshot("c1", "laptop", &["/a"], &["sak-plan-9"]),
            snapshot("a2", "laptop", &["/a"], &["sak-plan-7"]),
            snapshot("a1", "laptop", &["/a"], &["sak-plan-7"]),
            snapshot("old", "laptop", &["/a"], &[]),
        ];
        assert_eq!(newest_of(&snapshots, &plan).unwrap().id, "a2");

        // from before the tags: this computer and exactly the paths, no tag of a plan
        let before = [
            snapshot("more", "laptop", &["/a", "/b"], &[]),
            snapshot("other", "desktop", &["/a"], &[]),
            snapshot("c1", "laptop", &["/a"], &["sak-plan-9"]),
            snapshot("mine", "laptop", &["/a"], &["by-hand"]),
            snapshot("older", "laptop", &["/a"], &[]),
        ];
        assert_eq!(newest_of(&before, &plan).unwrap().id, "mine");
        // none of the plan
        assert!(newest_of(&before[..3], &plan).is_none());
        assert!(newest_of(&[], &plan).is_none());
    }
}
