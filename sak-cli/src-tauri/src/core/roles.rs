//! the markers of the roles on the server: ansible has no plan, so the server tells what was
//! applied last. Each run writes one marker for each role it applied (the role `role_markers`,
//! the last of the playbook), its fingerprint: the content of the role and what the run hands it.
//! The playbook removes the markers before the roles run, so a run that failed leaves none. A
//! plan reads the markers through ssh and compares them with the fingerprints of this sakctl: a
//! role whose files changed or that gets other values runs again, a role that stays as it was does
//! not. Each server applies the roles of one deployment, so the markers need no scope

use crate::core::provision::ProvisionRun;
use sak_cloud::api::{DiffItem, DiffStatus};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

include!(concat!(env!("OUT_DIR"), "/role_digests.rs"));

/// where the role `role_markers` writes the markers, one file for each role, root only
pub const MARKERS_DIR: &str = "/var/lib/sak/roles";

/// the roles of the playbook that the markers stand for, in their order; `role_markers` writes
/// them, it has none of its own
pub const ROLES: &[&str] = &["common", "storage", "backend"];

/// the variables of the inventory each role reads (`ProvisionRun::variables`); a test checks them
/// against the files of the roles. The backend also gets `backend.env`, which its fingerprint covers
const INPUTS: &[(&str, &[&str])] = &[
    ("common", &[]),
    ("storage", &["storage_device"]),
    (
        "backend",
        &[
            "sak_name",
            "sak_dns_mode",
            "sak_root_domain",
            "sak_acme_ca",
            "sak_api_public_key",
            "sak_admin_email",
            "sak_smtp_enabled",
            "sak_smtp_tls",
            "sak_smtp_starttls",
            "backend_env_src",
            "sak_version",
            "sak_image_tag",
        ],
    ),
];

/// the digest of the files of `role` (build.rs); empty for a role this build does not know
fn digest(role: &str) -> &'static str {
    ROLE_DIGESTS
        .iter()
        .find(|(name, _)| *name == role)
        .map(|(_, digest)| *digest)
        .unwrap_or_default()
}

fn inputs(role: &str) -> &'static [&'static str] {
    INPUTS
        .iter()
        .find(|(name, _)| *name == role)
        .map(|(_, inputs)| *inputs)
        .unwrap_or_default()
}

/// the fingerprint of each role of `run`: the sha-256 over its name, the digest of its files and
/// the values it gets, `name=value` in the order of their names, the backend also `backend.env`
pub fn fingerprints(run: &ProvisionRun) -> BTreeMap<String, String> {
    let variables: BTreeMap<&str, String> = run.variables().into_iter().collect();
    ROLES
        .iter()
        .map(|role| {
            let mut hash = Sha256::new();
            hash.update(role.as_bytes());
            hash.update(b"\n");
            hash.update(digest(role).as_bytes());
            hash.update(b"\n");
            let mut names: Vec<&str> = inputs(role).to_vec();
            names.sort_unstable();
            for name in names {
                let value = variables.get(name).map(String::as_str).unwrap_or_default();
                hash.update(format!("{name}={value}\n").as_bytes());
            }
            if *role == "backend" {
                hash.update(run.backend_env().as_bytes());
            }
            let hex = crate::core::encoding::hex(&hash.finalize());
            (role.to_string(), hex)
        })
        .collect()
}

/// the command that prints each marker of the server as `<role> <fingerprint>`; nothing for a
/// server without markers
pub fn read_command() -> String {
    format!(
        "for f in {MARKERS_DIR}/*; do [ -f \"$f\" ] && echo \"${{f##*/}} $(cat \"$f\")\"; done; exit 0"
    )
}

/// the markers that `read_command` printed, by role
pub fn parse(output: &str) -> BTreeMap<String, String> {
    output
        .lines()
        .filter_map(|line| {
            let (role, fingerprint) = line.trim().split_once(' ')?;
            let fingerprint = fingerprint.trim();
            (!role.is_empty() && !fingerprint.is_empty())
                .then(|| (role.to_string(), fingerprint.to_string()))
        })
        .collect()
}

/// the markers of the server against the fingerprints of this run: missing without any marker
/// (never provisioned, or the last run failed), changes for each role whose marker is not its
/// fingerprint
pub fn compare(
    expected: &BTreeMap<String, String>,
    found: &BTreeMap<String, String>,
) -> (DiffStatus, Vec<DiffItem>) {
    if found.is_empty() {
        return (DiffStatus::Missing, Vec::new());
    }
    let short = |fingerprint: &str| fingerprint.chars().take(12).collect::<String>();
    let changes: Vec<DiffItem> = expected
        .iter()
        .filter_map(|(role, fingerprint)| match found.get(role) {
            None => Some(DiffItem::missing(format!("role '{role}'"))),
            Some(marker) if marker != fingerprint => Some(DiffItem::changed(
                format!("role '{role}'"),
                short(fingerprint),
                short(marker),
            )),
            Some(_) => None,
        })
        .collect();
    if changes.is_empty() {
        (DiffStatus::UpToDate, changes)
    } else {
        (DiffStatus::HasChanges, changes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::provision::tests::run;

    #[test]
    fn every_role_of_the_playbook_has_a_digest() {
        for role in ROLES {
            assert_eq!(digest(role).len(), 64, "{role}");
        }
        assert_ne!(digest("common"), digest("backend"));
    }

    #[test]
    fn a_role_runs_again_only_when_its_values_change() {
        let before = fingerprints(&run());
        assert_eq!(
            before.keys().collect::<Vec<_>>(),
            ["backend", "common", "storage"]
        );
        assert_eq!(fingerprints(&run()), before);

        // another device: only the storage
        let moved = ProvisionRun {
            storage_device: "/dev/disk/by-id/scsi-0HC_Volume_2".to_string(),
            ..run()
        };
        let after = fingerprints(&moved);
        assert_eq!(after["common"], before["common"]);
        assert_ne!(after["storage"], before["storage"]);
        assert_eq!(after["backend"], before["backend"]);

        // another password of the mail server, in backend.env only: only the backend
        let mut changed = run();
        if let Some(smtp) = changed.smtp.as_mut() {
            smtp.password = "another".to_string();
        }
        let after = fingerprints(&changed);
        assert_eq!(after["common"], before["common"]);
        assert_eq!(after["storage"], before["storage"]);
        assert_ne!(after["backend"], before["backend"]);
    }

    #[test]
    fn the_markers_are_read_back_and_compared() {
        let expected = fingerprints(&run());
        let printed: String = expected
            .iter()
            .map(|(role, fingerprint)| format!("{role} {fingerprint}\n"))
            .collect();
        let found = parse(&printed);
        assert_eq!(found, expected);
        assert_eq!(
            compare(&expected, &found),
            (DiffStatus::UpToDate, Vec::new())
        );

        assert_eq!(
            compare(&expected, &BTreeMap::new()),
            (DiffStatus::Missing, Vec::new())
        );

        let mut other = found.clone();
        other.insert("backend".to_string(), "0".repeat(64));
        other.remove("storage");
        let (status, changes) = compare(&expected, &other);
        assert_eq!(status, DiffStatus::HasChanges);
        assert_eq!(
            changes
                .iter()
                .map(|change| change.log_text())
                .collect::<Vec<_>>()
                .len(),
            2
        );
        assert!(read_command().contains(MARKERS_DIR));
    }

    /// what a role reads of the inventory is among its inputs, so a change of it runs the role
    /// again; the variables are those of the inventory
    #[test]
    fn the_inputs_of_each_role_are_the_variables_it_reads() {
        let roles = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../ansible/roles");
        let names: Vec<&str> = run()
            .variables()
            .into_iter()
            .map(|(name, _)| name)
            .collect();
        for role in ROLES {
            let mut text = String::new();
            read_all(&roles.join(role), &mut text);
            for name in &names {
                if text.contains(name) {
                    assert!(
                        inputs(role).contains(name),
                        "the role {role} reads {name}, which is not among its inputs"
                    );
                }
            }
        }
        for (role, inputs) in INPUTS {
            for input in *inputs {
                assert!(
                    names.contains(input),
                    "{role}: {input} is no variable of the inventory"
                );
            }
        }
    }

    fn read_all(dir: &std::path::Path, text: &mut String) {
        for entry in std::fs::read_dir(dir).unwrap().flatten() {
            let path = entry.path();
            if path.is_dir() {
                if entry.file_name() != "tests" {
                    read_all(&path, text);
                }
            } else {
                text.push_str(&std::fs::read_to_string(&path).unwrap_or_default());
            }
        }
    }
}
