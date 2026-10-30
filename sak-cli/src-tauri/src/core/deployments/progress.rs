//! what an apply reports while it runs, the tasks of the playbook among it

use super::*;

/// what an apply reports while it runs: a resource started, a resource done, the provisioning.
/// The command itself answers when everything is done or failed
#[derive(Clone, Serialize)]
#[serde(tag = "event", rename_all = "camelCase")]
pub enum DeploymentEvent {
    Started {
        resource: String,
    },
    /// a resource done; `text` is the key of what was done, for the command line and the window
    Applied {
        resource: String,
        text: &'static str,
    },
    /// a resource removed, or gone already; `text` is the key of which
    Destroyed {
        resource: String,
        text: &'static str,
    },
    /// a message about the provisioning: it starts, or sakctl waits for the api
    Provisioning {
        message: String,
    },
    /// a line ansible printed while provisioning the server
    Output {
        line: String,
    },
    /// a task of the playbook begins; `resource` is the provisioning as the plan names it
    TaskStarted {
        resource: String,
        task: String,
    },
    /// a task of the playbook ended on the server; `message` is what ansible says when it failed
    TaskDone {
        resource: String,
        task: String,
        outcome: TaskOutcome,
        message: Option<String>,
    },
    /// the playbook failed; `task` when the events tell it
    ProvisionFailed {
        resource: String,
        task: Option<String>,
        message: String,
    },
}

/// how a task of the playbook ended on the server
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TaskOutcome {
    Ok,
    Changed,
    Skipped,
    /// failed, but the playbook continues
    Ignored,
    Failed,
    Unreachable,
}

/// the event of the apply for an event of the playbook; none for an event the apply does not show
pub(crate) fn task_event(resource: &str, event: AnsibleEvent) -> Option<DeploymentEvent> {
    let done = |task: String, outcome, message| {
        Some(DeploymentEvent::TaskDone {
            resource: resource.to_string(),
            task: task_name(&task),
            outcome,
            message,
        })
    };
    match event {
        AnsibleEvent::TaskStart { task } => Some(DeploymentEvent::TaskStarted {
            resource: resource.to_string(),
            task: task_name(&task),
        }),
        AnsibleEvent::Ok { task, changed, .. } => done(
            task,
            if changed {
                TaskOutcome::Changed
            } else {
                TaskOutcome::Ok
            },
            None,
        ),
        AnsibleEvent::Skipped { task, .. } => done(task, TaskOutcome::Skipped, None),
        AnsibleEvent::Failed {
            task,
            msg,
            ignore_errors,
            ..
        } => done(
            task,
            if ignore_errors {
                TaskOutcome::Ignored
            } else {
                TaskOutcome::Failed
            },
            msg,
        ),
        AnsibleEvent::Unreachable { task, msg, .. } => done(task, TaskOutcome::Unreachable, msg),
        AnsibleEvent::PlayStart { .. } | AnsibleEvent::Stats { .. } => None,
    }
}
