//! the plan of a create, deploy or destroy as the window and the command line show it: each
//! resource, its status, what the plan does to it and what the apply does when it starts

use super::*;

/// each resource of a plan with its status and its changes
pub type Seen = Vec<(String, DiffStatus, Vec<String>)>;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanResponse {
    pub(crate) name: String,
    pub(crate) public_key: String,
    pub(crate) resources: Vec<PlannedResource>,
}

impl PlanResponse {
    /// to tell whether the project changed since. Without the provisioning: a forced deploy
    /// changes its row on purpose
    pub fn seen(&self) -> Seen {
        self.resources
            .iter()
            .filter(|resource| resource.kind != PlannedKind::Provisioning)
            .map(|resource| {
                (
                    resource.resource.clone(),
                    resource.status,
                    resource.changes.clone(),
                )
            })
            .collect()
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlannedResource {
    /// `server 'alpha'`, the name the events of the apply use for it
    pub(crate) resource: String,
    /// the kind and the name at the provider, which the ui shows in its language
    pub(crate) kind: PlannedKind,
    pub(crate) name: String,
    /// the resource status of the glossary
    pub(crate) resource_status: ResourceStatus,
    /// what the plan does to it; none for a resource that stays as it is
    pub(crate) action: Option<PlanAction>,
    pub(crate) working: Working,
    pub(crate) status: DiffStatus,
    /// a destroy keeps it: the data volume, unless `delete_data` is set
    pub(crate) keep: bool,
    pub(crate) changes: Vec<String>,
}

/// a kind of the cloud, or the provisioning of the server; its text is
/// `common.deployments.resource.<kind>`
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum PlannedKind {
    Provisioning,
    #[serde(untagged)]
    Resource(ResourceKind),
}

/// the resource status of the glossary. A resource whose parent is missing is missing too; one of
/// someone else stops the plan before (NotOwned)
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

    pub fn text_key(self) -> &'static str {
        match self {
            ResourceStatus::UpToDate => "common.resource_status.up_to_date",
            ResourceStatus::Missing => "common.resource_status.missing",
            ResourceStatus::HasChanges => "common.resource_status.has_changes",
        }
    }
}

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
            PlanAction::WillCreate => "common.plan.will_create",
            PlanAction::WillUpdate => "common.plan.will_update",
            PlanAction::WillDestroy => "common.plan.will_destroy",
            PlanAction::WillKeep => "common.plan.will_keep",
            PlanAction::AlreadyDeleted => "common.plan.already_deleted",
            PlanAction::WillRun => "common.plan.will_run",
            PlanAction::RunsWhenForced => "common.plan.runs_when_forced",
        }
    }
}

/// what the apply does to a resource when it starts
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
pub enum Working {
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
            Working::Create => "common.working.working",
            Working::Update => "common.working.working_update",
            Working::Recreate => "common.working.working_recreate",
            Working::Check => "common.working.working_check",
            Working::Destroy => "common.working.working_destroy",
        }
    }
}

/// a resource created anew reads as created
pub(super) fn applied_key(applied: Applied) -> &'static str {
    match applied {
        Applied::Created | Applied::Recreated => "common.done.created",
        Applied::Updated => "common.done.updated",
        Applied::Unchanged => ResourceStatus::UpToDate.text_key(),
    }
}

pub(super) fn destroyed_key(gone: bool) -> &'static str {
    if gone {
        "common.done.destroyed"
    } else {
        PlanAction::AlreadyDeleted.text_key()
    }
}

/// the serialized name of an enum value (`up_to_date`); the command line uses it as a text key
pub(crate) fn key<T: Serialize>(value: &T) -> String {
    serde_json::to_value(value)
        .ok()
        .and_then(|value| value.as_str().map(str::to_string))
        .unwrap_or_default()
}

/// a stale dns record (`remove`) goes; a destroy keeps the data volume (`keep`) and removes the
/// rest; a create or deploy creates what is missing or must be recreated, updates what changed
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

/// what the apply does to a resource when it starts
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
