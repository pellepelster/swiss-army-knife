//! the plan of a create, deploy or destroy as the window and the command line show it: each
//! resource, its status, what the plan does to it and what the apply does when it starts

use super::*;

/// the plan of a create, deploy or destroy: what the apply does, resource by resource
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanResponse {
    pub(crate) name: String,
    pub(crate) public_key: String,
    pub(crate) resources: Vec<PlannedResource>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlannedResource {
    /// `server 'alpha'`, the name the events of the apply use for it
    pub(crate) resource: String,
    /// the kind of resource, `ssh_key`, `volume`, …, `provisioning`, and its name at the
    /// provider: the page and the command line show the resource with them, in their language
    pub(crate) kind: String,
    pub(crate) name: String,
    /// the resource status of the glossary
    pub(crate) resource_status: ResourceStatus,
    /// what the plan does to it; none for a resource that stays as it is
    pub(crate) action: Option<PlanAction>,
    /// what the apply does to it when it starts
    pub(crate) working: Working,
    pub(crate) status: DiffStatus,
    /// a destroy keeps it: the data volume, unless `delete_data` is set
    pub(crate) keep: bool,
    pub(crate) changes: Vec<String>,
}

/// the resource status of the glossary for a status of a diff. A resource whose parent is missing
/// is missing too. A resource found under another name stops the plan before (NotOwned)
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ResourceStatus {
    UpToDate,
    Missing,
    HasChanges,
}

impl ResourceStatus {
    pub fn of(status: DiffStatus) -> Self {
        match status {
            DiffStatus::UpToDate => ResourceStatus::UpToDate,
            DiffStatus::Missing | DiffStatus::ParentMissing => ResourceStatus::Missing,
            DiffStatus::HasChanges | DiffStatus::Duplicate => ResourceStatus::HasChanges,
        }
    }

    /// the key of its text, for the command line and the window alike
    pub fn text_key(self) -> &'static str {
        match self {
            ResourceStatus::UpToDate => "shared.resource_status.up_to_date",
            ResourceStatus::Missing => "shared.resource_status.missing",
            ResourceStatus::HasChanges => "shared.resource_status.has_changes",
        }
    }
}

/// what the plan does to a resource
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum PlanAction {
    WillCreate,
    WillUpdate,
    WillDestroy,
    /// a destroy keeps it: the data volume
    WillKeep,
    /// a destroy finds it gone already
    AlreadyDeleted,
    /// the playbook runs
    WillRun,
    /// the playbook is up to date, it runs on a forced deploy
    RunsWhenForced,
}

impl PlanAction {
    pub fn text_key(self) -> &'static str {
        match self {
            PlanAction::WillCreate => "shared.plan.will_create",
            PlanAction::WillUpdate => "shared.plan.will_update",
            PlanAction::WillDestroy => "shared.plan.will_destroy",
            PlanAction::WillKeep => "shared.plan.will_keep",
            PlanAction::AlreadyDeleted => "shared.plan.already_deleted",
            PlanAction::WillRun => "shared.plan.will_run",
            PlanAction::RunsWhenForced => "shared.plan.runs_when_forced",
        }
    }
}

/// what the apply does to a resource when it starts
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
pub enum Working {
    /// creates it
    #[serde(rename = "working")]
    Create,
    #[serde(rename = "working_update")]
    Update,
    #[serde(rename = "working_recreate")]
    Recreate,
    /// compares it, it is up to date
    #[serde(rename = "working_check")]
    Check,
    #[serde(rename = "working_destroy")]
    Destroy,
}

impl Working {
    pub fn text_key(self) -> &'static str {
        match self {
            Working::Create => "shared.working.working",
            Working::Update => "shared.working.working_update",
            Working::Recreate => "shared.working.working_recreate",
            Working::Check => "shared.working.working_check",
            Working::Destroy => "shared.working.working_destroy",
        }
    }
}

/// the key of the text of an apply's result: a resource created anew reads as created, one that
/// stays as up-to-date
pub(super) fn applied_key(applied: Applied) -> &'static str {
    match applied {
        Applied::Created | Applied::Recreated => "shared.done.created",
        Applied::Updated => "shared.done.updated",
        Applied::Unchanged => ResourceStatus::UpToDate.text_key(),
    }
}

/// the key of the text of a destroyed resource: destroyed, or gone already
pub(super) fn destroyed_key(gone: bool) -> &'static str {
    if gone {
        "shared.done.destroyed"
    } else {
        PlanAction::AlreadyDeleted.text_key()
    }
}

/// the name of a serialized enum value, `up_to_date`, `created`: the page reads it in the json,
/// the command line uses it as the key of its texts
pub(crate) fn key<T: Serialize>(value: &T) -> String {
    serde_json::to_value(value)
        .ok()
        .and_then(|value| value.as_str().map(str::to_string))
        .unwrap_or_default()
}

/// what the plan does to a resource, from its status. A stale dns record (`remove`) goes. A
/// destroy keeps the data volume (`keep`), leaves what is gone and removes the rest. A create or
/// deploy creates what is missing or must be created anew and updates what has changes
pub(super) fn action(
    intent: Intent,
    status: DiffStatus,
    recreate: bool,
    keep: bool,
    remove: bool,
) -> Option<PlanAction> {
    let missing = matches!(status, DiffStatus::Missing | DiffStatus::ParentMissing);
    match intent {
        _ if remove => Some(PlanAction::WillDestroy),
        Intent::Destroy if keep => Some(PlanAction::WillKeep),
        Intent::Destroy if missing => Some(PlanAction::AlreadyDeleted),
        Intent::Destroy => Some(PlanAction::WillDestroy),
        _ if missing || recreate => Some(PlanAction::WillCreate),
        _ if status == DiffStatus::UpToDate => None,
        _ => Some(PlanAction::WillUpdate),
    }
}

/// what the apply does to a resource when it starts, from what the plan found: it creates what is
/// missing, checks what is up-to-date, updates or recreates what has changes, and destroys on a
/// destroy and a stale dns record
pub(super) fn working(intent: Intent, status: DiffStatus, recreate: bool, remove: bool) -> Working {
    match (intent, status) {
        _ if remove => Working::Destroy,
        (Intent::Destroy, _) => Working::Destroy,
        (_, DiffStatus::Missing | DiffStatus::ParentMissing) => Working::Create,
        (_, DiffStatus::UpToDate) => Working::Check,
        _ if recreate => Working::Recreate,
        _ => Working::Update,
    }
}
