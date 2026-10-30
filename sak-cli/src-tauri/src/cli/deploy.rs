//! the commands about deployments as a whole: the list, and a create, deploy or destroy with its
//! plan

use super::*;

pub(super) async fn list(
    manager: &DeploymentManager,
    provider: &HetznerToken,
) -> Result<(), SakctlError> {
    let deployments = manager.list(provider.tokens()).await?;
    if deployments.is_empty() {
        log(Level::Info, t!("shared.deployments.empty"));
    }
    for deployment in deployments {
        let location = deployment
            .location
            .map(|location| location.to_string())
            .unwrap_or_default();
        log(
            Level::Info,
            format!(
                "{} {} {} {} {}",
                deployment.name,
                t!(format!(
                    "shared.deployments.status.{}",
                    key(&deployment.status)
                )),
                location,
                deployment.provider.name,
                deployment.frontend_url.as_deref().unwrap_or_default()
            )
            .trim_end(),
        );
        if let Some(error) = &deployment.error {
            log(
                Level::Warn,
                t!(
                    "shared.deployments.not_compared",
                    name = deployment.name,
                    error = error
                ),
            );
        }
        for resource in deployment.resources {
            let resource_name = title(&key(&resource.kind), &resource.name);
            // its status, then its detail; a resource that was not compared shows its detail only
            let status = resource.status.map(ResourceStatus::of);
            let detail = resource
                .detail
                .filter(|_| status != Some(ResourceStatus::Missing));
            let line = match (status, detail) {
                (Some(status), Some(detail)) => {
                    format!("{resource_name} {}, {detail}", t!(status.text_key()))
                }
                (Some(status), None) => format!("{resource_name} {}", t!(status.text_key())),
                (None, detail) => format!("{resource_name} {}", detail.unwrap_or_default()),
            };
            log(Level::Info, line.trim_end());
            for change in resource.changes {
                log(Level::Warn, format!("{resource_name} {change}"));
            }
        }
    }
    Ok(())
}

/// a create, deploy or destroy: plans, prints the plan and applies it, as the plan page of the
/// window does. Prints each resource when it is done; the output of ansible only with `show_log`
pub(super) async fn plan_and_apply(
    manager: &DeploymentManager,
    provider: &HetznerToken,
    deployment: &DeploymentRequest,
    runtime: &ContainerRuntime,
    show_log: bool,
) -> Result<(), SakctlError> {
    // the project is read once, for the plan and the apply
    let prepared = manager.prepare(provider.tokens(), deployment).await?;
    let plan = manager
        .plan_prepared(&prepared, deployment, runtime)
        .await?;
    let intro = match deployment.intent {
        Intent::Create => t!("shared.plan.intro"),
        Intent::Deploy => t!("shared.plan.intro_deploy"),
        Intent::Destroy => t!("shared.plan.intro_destroy"),
    };
    log(Level::Info, format!("{intro} {}", plan.name));
    // each resource by its kind and name in the language of the command line
    let titles: HashMap<String, String> = plan
        .resources
        .iter()
        .map(|resource| {
            (
                resource.resource.clone(),
                title(&key(&resource.kind), &resource.name),
            )
        })
        .collect();
    let titled = |resource: &str| {
        titles
            .get(resource)
            .cloned()
            .unwrap_or_else(|| resource.to_string())
    };
    for resource in &plan.resources {
        // its status, then what the plan does to it
        let status = t!(resource.resource_status.text_key());
        let action = resource
            .action
            .map(|action| format!(", {}", t!(action.text_key())))
            .unwrap_or_default();
        // a resource that the apply removes is a warning
        let level = if resource.action == Some(PlanAction::WillDestroy) {
            Level::Warn
        } else {
            Level::Info
        };
        let title = titled(&resource.resource);
        log(level, format!("{title} {status}{action}"));
        for change in &resource.changes {
            log(Level::Warn, format!("{title} {change}"));
        }
    }

    // what the apply does to a resource when it starts, from what the plan found
    let working: HashMap<String, Working> = plan
        .resources
        .iter()
        .map(|resource| (resource.resource.clone(), resource.working))
        .collect();
    // a resource, then what is done to it, with the texts of the window: `Server 'alpha': created`
    let mut report = |event: DeploymentEvent| match event {
        DeploymentEvent::Started { resource } => {
            let working = working.get(&resource).copied().unwrap_or(Working::Create);
            log(
                Level::Info,
                format!("{}: {}", titled(&resource), t!(working.text_key())),
            )
        }
        DeploymentEvent::Applied { resource, text }
        | DeploymentEvent::Destroyed { resource, text } => {
            log(Level::Info, format!("{}: {}", titled(&resource), t!(text)))
        }
        DeploymentEvent::Provisioning { message } => log(Level::Info, message),
        // the log keeps the text of a line of ansible without its spaces and empty lines
        DeploymentEvent::Output { line } => {
            if show_log && !line.trim().is_empty() {
                log(Level::Info, format!("[ansible] {}", line.trim()))
            }
        }
        // one line for each task of the playbook when it ends; a failure as an error
        DeploymentEvent::TaskDone {
            task,
            outcome,
            message,
            ..
        } => {
            let level = match outcome {
                TaskOutcome::Failed | TaskOutcome::Unreachable => Level::Error,
                TaskOutcome::Ignored => Level::Warn,
                _ => Level::Info,
            };
            log(
                level,
                t!(
                    format!("cli.task.{}", key(&outcome)),
                    task = task,
                    message = message.unwrap_or_default()
                ),
            )
        }
        // the error of the run tells what failed, the command ends with it
        DeploymentEvent::TaskStarted { .. } | DeploymentEvent::ProvisionFailed { .. } => {}
    };
    manager
        .apply(prepared, deployment, runtime, &mut report)
        .await?;
    let done = match deployment.intent {
        Intent::Destroy if deployment.delete_data => {
            t!("cli.destroyed_with_data", name = deployment.name)
        }
        Intent::Destroy => t!("cli.destroyed", name = deployment.name),
        _ => t!("cli.done", name = deployment.name),
    };
    log(Level::Info, done);
    Ok(())
}
