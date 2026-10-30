use crate::api::resource::{Labels, Resource, ResourceRef};
use std::fmt::{self, Write};

/// what kind of difference a [DiffItem] is
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ChangeKind {
    /// a value the provider can change in place
    Changed,
    /// a value that only a recreate of the resource can change
    Recreate,
    /// something declared that the runtime does not have
    Missing,
    /// something the runtime has that is not declared
    Removed,
}

/// one difference between a declared resource and its runtime
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DiffItem {
    /// what differs, e.g. `image` or `label 'foo'`
    pub name: String,
    pub kind: ChangeKind,
    pub expected: Option<String>,
    pub actual: Option<String>,
    /// a label of the resource, which no dependent reads
    pub label: bool,
}

impl DiffItem {
    fn new(name: impl Into<String>, kind: ChangeKind) -> Self {
        DiffItem {
            name: name.into(),
            kind,
            expected: None,
            actual: None,
            label: false,
        }
    }

    /// a value the provider can change in place
    pub fn changed(
        name: impl Into<String>,
        expected: impl ToString,
        actual: impl ToString,
    ) -> Self {
        DiffItem {
            expected: Some(expected.to_string()),
            actual: Some(actual.to_string()),
            ..DiffItem::new(name, ChangeKind::Changed)
        }
    }

    /// something that changes in place without values to show, e.g. a server another resource
    /// runs on
    pub fn changed_unknown(name: impl Into<String>) -> Self {
        DiffItem::new(name, ChangeKind::Changed)
    }

    /// a value that only a recreate of the resource can change
    pub fn recreate(
        name: impl Into<String>,
        expected: impl ToString,
        actual: impl ToString,
    ) -> Self {
        DiffItem {
            kind: ChangeKind::Recreate,
            ..DiffItem::changed(name, expected, actual)
        }
    }

    /// something declared that the runtime does not have
    pub fn missing(name: impl Into<String>) -> Self {
        DiffItem::new(name, ChangeKind::Missing)
    }

    /// something the runtime has that is not declared
    pub fn removed(name: impl Into<String>) -> Self {
        DiffItem::new(name, ChangeKind::Removed)
    }

    /// the difference is in a label of the resource
    pub fn of_label(mut self) -> Self {
        self.label = true;
        self
    }

    pub fn with_actual(mut self, actual: Option<impl ToString>) -> Self {
        self.actual = actual.map(|actual| actual.to_string());
        self
    }

    /// the provider cannot change this in place: the resource must be destroyed and created
    pub fn triggers_recreate(&self) -> bool {
        self.kind == ChangeKind::Recreate
    }

    pub fn log_text(&self) -> String {
        match self.kind {
            ChangeKind::Missing => format!("{} is missing", self.name),
            ChangeKind::Removed => format!("{} is not declared", self.name),
            ChangeKind::Changed | ChangeKind::Recreate
                if self.expected.is_some() || self.actual.is_some() =>
            {
                format!(
                    "{} should be '{}' but was '{}'",
                    self.name,
                    self.expected.as_deref().unwrap_or("<empty>"),
                    self.actual.as_deref().unwrap_or("<empty>")
                )
            }
            ChangeKind::Recreate => format!("{} changed and requires recreate", self.name),
            ChangeKind::Changed => format!("{} changed", self.name),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DiffStatus {
    /// the resource does not exist at the provider
    Missing,
    /// a resource that this one depends on does not exist; this one is not diffed
    ParentMissing,
    UpToDate,
    HasChanges,
    /// the resource does not exist under its name, but one with the same content does under
    /// another name (an ssh key with the same fingerprint)
    Duplicate,
}

/// the diff of one declared resource against its runtime
#[derive(Clone, Debug)]
pub struct ResourceDiff {
    pub resource: ResourceRef,
    pub status: DiffStatus,
    pub changes: Vec<DiffItem>,
    /// the message that names the duplicate, for [DiffStatus::Duplicate]
    pub duplicate_message: Option<String>,
}

impl ResourceDiff {
    pub fn new(resource: &impl Resource, status: DiffStatus) -> Self {
        ResourceDiff {
            resource: resource.resource_ref(),
            status,
            changes: Vec::new(),
            duplicate_message: None,
        }
    }

    pub fn missing(resource: &impl Resource) -> Self {
        ResourceDiff::new(resource, DiffStatus::Missing)
    }

    pub fn parent_missing(resource: &impl Resource) -> Self {
        ResourceDiff::new(resource, DiffStatus::ParentMissing)
    }

    pub fn duplicate(resource: &impl Resource, message: impl Into<String>) -> Self {
        ResourceDiff {
            duplicate_message: Some(message.into()),
            ..ResourceDiff::new(resource, DiffStatus::Duplicate)
        }
    }

    /// up to date without changes, else has changes
    pub fn from_changes(resource: &impl Resource, changes: Vec<DiffItem>) -> Self {
        if changes.is_empty() {
            ResourceDiff::new(resource, DiffStatus::UpToDate)
        } else {
            ResourceDiff {
                changes,
                ..ResourceDiff::new(resource, DiffStatus::HasChanges)
            }
        }
    }

    pub fn needs_recreate(&self) -> bool {
        self.changes.iter().any(|change| change.triggers_recreate())
    }

    pub fn has_changes(&self) -> bool {
        !self.changes.is_empty()
    }

    /// whether the change alters what a dependent reads of the resource: a create, a recreate,
    /// a changed value. A change of labels only does not
    pub fn changes_what_dependents_read(&self) -> bool {
        match self.status {
            DiffStatus::UpToDate => false,
            DiffStatus::HasChanges => {
                self.changes.is_empty() || self.changes.iter().any(|change| !change.label)
            }
            DiffStatus::Missing | DiffStatus::ParentMissing | DiffStatus::Duplicate => true,
        }
    }
}

impl fmt::Display for ResourceDiff {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self.status {
            DiffStatus::Missing => write!(f, "{} is missing", self.resource),
            DiffStatus::ParentMissing => write!(f, "{} has a missing parent", self.resource),
            DiffStatus::Duplicate => write!(
                f,
                "{}",
                self.duplicate_message.as_deref().unwrap_or("is duplicated")
            ),
            DiffStatus::UpToDate => write!(f, "{} is up to date", self.resource),
            DiffStatus::HasChanges => {
                let changes: Vec<String> = self.changes.iter().map(DiffItem::log_text).collect();
                write!(f, "{} has changes: {}", self.resource, changes.join(", "))
            }
        }
    }
}

/// the diffs of a run as a report, every resource under its status: the changed resources with
/// their changes, the missing ones, those with a missing parent, the duplicates with what they
/// duplicate, the up to date ones
pub fn log_text(diffs: &[ResourceDiff]) -> String {
    let mut text = String::new();
    writeln!(text, "changed resources:").unwrap();
    for diff in diffs
        .iter()
        .filter(|diff| diff.status == DiffStatus::HasChanges)
    {
        writeln!(text, "    - {}", diff.resource).unwrap();
        for change in &diff.changes {
            writeln!(text, "        - {}", change.log_text()).unwrap();
        }
    }
    for (title, status) in [
        ("missing resources", DiffStatus::Missing),
        ("resources with a missing parent", DiffStatus::ParentMissing),
        ("duplicate resources", DiffStatus::Duplicate),
        ("up to date resources", DiffStatus::UpToDate),
    ] {
        writeln!(text, "{title}:").unwrap();
        for diff in diffs.iter().filter(|diff| diff.status == status) {
            match &diff.duplicate_message {
                Some(message) => writeln!(text, "    - {}: {message}", diff.resource).unwrap(),
                None => writeln!(text, "    - {}", diff.resource).unwrap(),
            }
        }
    }
    text
}

/// compares the declared labels with the live labels: missing, changed and not declared.
/// The keys `ignored` tells are left out on both sides: the labels the provisioners keep for
/// themselves (e.g. the hash of the user data) and the info labels, which are never compared
pub fn label_diff(
    declared: &Labels,
    live: &Labels,
    ignored: impl Fn(&str) -> bool,
) -> Vec<DiffItem> {
    let compared = || declared.iter().filter(|(key, _)| !ignored(key));

    let missing = compared()
        .filter(|(key, _)| !live.contains_key(*key))
        .map(|(key, _)| DiffItem::missing(format!("label '{key}'")).of_label());

    let changed = compared().filter_map(|(key, value)| {
        let actual = live.get(key)?;
        (actual != value)
            .then(|| DiffItem::changed(format!("label '{key}'"), value, actual).of_label())
    });

    let removed = live
        .keys()
        .filter(|key| !ignored(key) && !declared.contains_key(*key))
        .map(|key| DiffItem::removed(format!("label '{key}'")).of_label());

    missing.chain(changed).chain(removed).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn labels(pairs: &[(&str, &str)]) -> Labels {
        pairs
            .iter()
            .map(|(key, value)| (key.to_string(), value.to_string()))
            .collect()
    }

    #[test]
    fn label_diff_reports_missing_changed_and_removed_labels() {
        let declared = labels(&[("a", "1"), ("b", "2"), ("c", "3")]);
        let live = labels(&[("b", "changed"), ("c", "3"), ("d", "4")]);

        let diff = label_diff(&declared, &live, |_| false);

        assert_eq!(
            diff,
            vec![
                DiffItem::missing("label 'a'").of_label(),
                DiffItem::changed("label 'b'", "2", "changed").of_label(),
                DiffItem::removed("label 'd'").of_label(),
            ]
        );
        assert_eq!(diff[0].log_text(), "label 'a' is missing");
        assert_eq!(
            diff[1].log_text(),
            "label 'b' should be '2' but was 'changed'"
        );
        assert_eq!(diff[2].log_text(), "label 'd' is not declared");
    }

    #[test]
    fn label_diff_is_empty_for_equal_labels() {
        let declared = labels(&[("a", "1")]);

        assert!(label_diff(&declared, &declared.clone(), |_| false).is_empty());
    }

    #[test]
    fn label_diff_leaves_ignored_keys_out_on_both_sides() {
        let declared = labels(&[("a", "1"), ("sak/user-data_0", "abc")]);
        let live = labels(&[
            ("a", "1"),
            ("sak/user-data_0", "def"),
            ("sak/user-data_1", "ghi"),
            ("sak/ssh-keys_0", "x"),
        ]);

        let managed =
            |key: &str| key.starts_with("sak/user-data") || key.starts_with("sak/ssh-keys");
        assert!(label_diff(&declared, &live, managed).is_empty());
        assert_eq!(label_diff(&declared, &live, |_| false).len(), 3);
    }

    #[test]
    fn the_report_shows_every_status() {
        let diff = |name: &str, status: DiffStatus| ResourceDiff {
            resource: ResourceRef {
                type_id: std::any::TypeId::of::<()>(),
                name: name.to_string(),
                log_text: format!("thing '{name}'"),
            },
            status,
            changes: Vec::new(),
            duplicate_message: None,
        };
        let diffs = [
            ResourceDiff {
                changes: vec![DiffItem::changed("size", 2, 1)],
                ..diff("a", DiffStatus::HasChanges)
            },
            diff("b", DiffStatus::Missing),
            diff("c", DiffStatus::ParentMissing),
            ResourceDiff {
                duplicate_message: Some("another key has its fingerprint".to_string()),
                ..diff("d", DiffStatus::Duplicate)
            },
            diff("e", DiffStatus::UpToDate),
        ];

        assert_eq!(
            log_text(&diffs),
            "changed resources:\n    - thing 'a'\n        - size should be '2' but was '1'\n\
             missing resources:\n    - thing 'b'\n\
             resources with a missing parent:\n    - thing 'c'\n\
             duplicate resources:\n    - thing 'd': another key has its fingerprint\n\
             up to date resources:\n    - thing 'e'\n"
        );
    }

    #[test]
    fn a_change_of_labels_only_changes_nothing_a_dependent_reads() {
        let diff = |status: DiffStatus, changes: Vec<DiffItem>| ResourceDiff {
            resource: ResourceRef {
                type_id: std::any::TypeId::of::<()>(),
                name: "a".to_string(),
                log_text: "thing 'a'".to_string(),
            },
            status,
            changes,
            duplicate_message: None,
        };
        let label = DiffItem::changed("label 'b'", "2", "1").of_label();

        assert!(!diff(DiffStatus::HasChanges, vec![label.clone()]).changes_what_dependents_read());
        assert!(!diff(DiffStatus::UpToDate, Vec::new()).changes_what_dependents_read());
        for changes in [
            vec![label.clone(), DiffItem::changed("ttl", 60, 300)],
            vec![DiffItem::recreate("fingerprint", "aa", "bb")],
            Vec::new(),
        ] {
            assert!(diff(DiffStatus::HasChanges, changes).changes_what_dependents_read());
        }
        for status in [
            DiffStatus::Missing,
            DiffStatus::ParentMissing,
            DiffStatus::Duplicate,
        ] {
            assert!(diff(status, Vec::new()).changes_what_dependents_read());
        }
    }

    #[test]
    fn diff_item_log_texts() {
        assert_eq!(
            DiffItem::recreate("image", "debian-13", "debian-12").log_text(),
            "image should be 'debian-13' but was 'debian-12'"
        );
        assert_eq!(
            DiffItem::changed("ip", "10.0.0.1", "")
                .with_actual(None::<String>)
                .log_text(),
            "ip should be '10.0.0.1' but was '<empty>'"
        );
        assert!(DiffItem::recreate("type", "a", "b").triggers_recreate());
        assert!(!DiffItem::changed("type", "a", "b").triggers_recreate());
        assert_eq!(
            DiffItem::changed_unknown("server").log_text(),
            "server changed"
        );
        assert_eq!(
            DiffItem::removed("label 'd'").log_text(),
            "label 'd' is not declared"
        );
    }
}
