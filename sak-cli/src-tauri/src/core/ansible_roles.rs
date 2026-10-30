//! the provisioning of the server as a resource of the plan: the roles of the playbook on the
//! server of a deployment. It depends on the server and the data volume, so the registry plans
//! and applies it after them, as any other resource. Its diff asks the server for the markers of
//! the roles (`core::roles`): a server that changes in this run, a server without markers and a
//! role whose marker is not its fingerprint need a run. Its apply runs the playbook in the image of
//! the tools (`core::provision`), each line and task an event of the run. It has nothing to
//! destroy: the markers go with the server. sakctl declares it, sak-cloud does not know ansible

use crate::core::container::ContainerRuntime;
use crate::core::deployments::DeploymentEvent;
use crate::core::provision::{ProvisionFailure, ProvisionManager, ProvisionOutput, ProvisionRun};
use crate::core::roles;
use crate::core::ssh::{SSH_FAILED, SshTarget, Stream, run_streaming};
use async_trait::async_trait;
use rust_i18n::t;
use sak_cloud::api::context::LookupContextExt;
use sak_cloud::api::{
    ApplyContext, DestroyContext, DiffContext, DiffItem, DiffStatus, Error, Labeled, Labels,
    Lookup, LookupContext, LookupProvider, Provisioner, Resource, ResourceDiff, ResourceRef,
    Result, Runtime,
};
use sak_cloud::hetzner::dns_record::first_address;
use sak_cloud::hetzner::server::{HetznerServerLookup, HetznerServerRuntime};
use sak_cloud::hetzner::volume::{HetznerVolumeLookup, HetznerVolumeRuntime};
use tokio::sync::mpsc::UnboundedSender;

/// the roles of the playbook on the server of a deployment, named like the server
#[derive(Clone)]
pub struct AnsibleRoles {
    pub name: String,
    pub server: HetznerServerLookup,
    pub volume: HetznerVolumeLookup,
}

#[derive(Clone)]
pub struct AnsibleRolesLookup {
    name: String,
}

/// the roles as they were applied; they have no labels of their own
pub struct AnsibleRolesRuntime;

impl Labeled for AnsibleRolesRuntime {
    fn labels(&self) -> &Labels {
        static NONE: Labels = Labels::new();
        &NONE
    }
}

impl Runtime for AnsibleRolesRuntime {}

impl Lookup for AnsibleRolesLookup {
    type Runtime = AnsibleRolesRuntime;

    fn name(&self) -> &str {
        &self.name
    }

    fn log_text(&self) -> String {
        t!("shared.provision.resource", name = self.name.as_str()).to_string()
    }
}

impl Resource for AnsibleRoles {
    type Runtime = AnsibleRolesRuntime;
    type Lookup = AnsibleRolesLookup;

    fn name(&self) -> &str {
        &self.name
    }

    fn as_lookup(&self) -> AnsibleRolesLookup {
        AnsibleRolesLookup {
            name: self.name.clone(),
        }
    }

    fn depends_on(&self) -> Vec<ResourceRef> {
        vec![self.server.resource_ref(), self.volume.resource_ref()]
    }
}

/// whether `reference` is the provisioning, for the kind of a row of the plan
pub fn is_provisioning(reference: &ResourceRef) -> bool {
    reference.type_id == std::any::TypeId::of::<AnsibleRolesLookup>()
}

/// the provisioner of one run: what the playbook gets but the addresses and the device (the
/// lookups of the server and the volume tell them), the container runtime, whether the deploy is
/// forced and where the events of the playbook go. A failed playbook is an
/// `Error::Provisioning` with its task
pub struct AnsibleRolesProvisioner {
    pub run: ProvisionRun,
    pub runtime: ContainerRuntime,
    pub force: bool,
    pub events: Option<UnboundedSender<DeploymentEvent>>,
}

impl AnsibleRolesProvisioner {
    fn send(&self, event: DeploymentEvent) {
        if let Some(events) = &self.events {
            let _ = events.send(event);
        }
    }

    /// the run with the addresses of the server and the device of its volume; none when either
    /// is not there
    async fn run_on(
        &self,
        resource: &AnsibleRoles,
        context: &(impl LookupContext + ?Sized),
    ) -> Result<Option<ProvisionRun>> {
        let server: Option<HetznerServerRuntime> = context.lookup(&resource.server).await?;
        let volume: Option<HetznerVolumeRuntime> = context.lookup(&resource.volume).await?;
        let (Some(server), Some(volume)) = (server, volume) else {
            return Ok(None);
        };
        let Some(host) = server.public_ipv4.clone() else {
            return Ok(None);
        };
        Ok(Some(ProvisionRun {
            host,
            host_ipv6: server.public_ipv6.as_deref().map(first_address),
            storage_device: volume.device,
            ..self.run.clone()
        }))
    }

    /// the markers of the server, read through ssh in the image of the tools
    async fn markers(
        &self,
        run: &ProvisionRun,
    ) -> std::result::Result<std::collections::BTreeMap<String, String>, String> {
        let runtime = self
            .runtime
            .get()
            .ok_or_else(|| t!("shared.provision.no_runtime").to_string())?;
        let target = SshTarget {
            host: run.host.clone(),
            host_ipv6: run.host_ipv6.clone(),
            client_key: run.client_key.clone(),
            host_key: run.host_key.clone(),
        };
        tokio::task::spawn_blocking(move || {
            let mut out = Vec::new();
            // what ssh says when it fails, e.g. "Host key verification failed."
            let mut last_line = None;
            let code = run_streaming(
                runtime,
                &target,
                &[roles::read_command()],
                Stream::Out(&mut out),
                &mut |line| last_line = Some(line),
            )?;
            match code {
                0 => Ok(roles::parse(&String::from_utf8_lossy(&out))),
                SSH_FAILED => Err(crate::core::ssh::failed(last_line.as_deref())),
                code => Err(t!("shared.provision.markers_failed", code = code).to_string()),
            }
        })
        .await
        .map_err(|error| error.to_string())?
    }
}

#[async_trait]
impl LookupProvider<AnsibleRolesLookup> for AnsibleRolesProvisioner {
    /// the roles are no resource of the provider; a lookup finds nothing, nothing belongs to
    /// someone else
    async fn lookup(
        &self,
        _: &AnsibleRolesLookup,
        _: &dyn LookupContext,
    ) -> Result<Option<AnsibleRolesRuntime>> {
        Ok(None)
    }
}

#[async_trait]
impl Provisioner<AnsibleRoles> for AnsibleRolesProvisioner {
    async fn diff(
        &self,
        resource: &AnsibleRoles,
        context: &dyn DiffContext,
    ) -> Result<ResourceDiff> {
        let changed = |changes: Vec<DiffItem>| ResourceDiff {
            changes,
            ..ResourceDiff::new(resource, DiffStatus::HasChanges)
        };
        // a server or volume that changes in the plan, or changed in the apply of this run, runs
        // the roles again, whatever the markers say
        let server_changes = [
            resource.server.resource_ref(),
            resource.volume.resource_ref(),
        ]
        .iter()
        .any(|dependency| {
            context.has_pending_change(dependency) || context.applied_with_change(dependency)
        });
        if server_changes {
            return Ok(changed(vec![DiffItem::changed_unknown("server")]));
        }
        let Some(run) = self.run_on(resource, context).await? else {
            return Ok(ResourceDiff::missing(resource));
        };
        let (status, changes) = match self.markers(&run).await {
            Ok(found) => roles::compare(&roles::fingerprints(&run), &found),
            Err(error) => (
                DiffStatus::HasChanges,
                vec![DiffItem::changed("markers of the roles", "readable", error)],
            ),
        };
        // a forced deploy runs the playbook whatever the markers say
        if self.force && status == DiffStatus::UpToDate {
            return Ok(changed(Vec::new()));
        }
        Ok(ResourceDiff {
            changes,
            ..ResourceDiff::new(resource, status)
        })
    }

    async fn apply(
        &self,
        resource: &AnsibleRoles,
        context: &dyn ApplyContext,
    ) -> Result<AnsibleRolesRuntime> {
        let run = self.run_on(resource, context).await?.ok_or_else(|| {
            Error::message(t!(
                "shared.provision.no_address",
                name = self.run.name.as_str()
            ))
        })?;
        let name = resource.as_lookup().log_text();
        self.send(DeploymentEvent::Provisioning {
            message: t!(
                "shared.provision.running",
                name = run.name.as_str(),
                host = run.host.as_str()
            )
            .to_string(),
        });
        // ansible runs as a process of its own; its lines come through a channel while it runs
        let runtime = self.runtime.clone();
        let (sender, mut receiver) = tokio::sync::mpsc::unbounded_channel();
        let provisioning = tokio::task::spawn_blocking(move || {
            ProvisionManager.provision(&runtime, &run, &mut |output| {
                let _ = sender.send(output);
            })
        });
        while let Some(output) = receiver.recv().await {
            let event = match output {
                ProvisionOutput::Line(line) => Some(DeploymentEvent::Output { line }),
                ProvisionOutput::Event(event) => crate::core::deployments::task_event(&name, event),
            };
            if let Some(event) = event {
                self.send(event);
            }
        }
        let result = provisioning
            .await
            .map_err(|error| Error::message(error.to_string()))?;
        if let Err(ProvisionFailure { task, message }) = result {
            self.send(DeploymentEvent::ProvisionFailed {
                resource: name,
                task: task.clone(),
                message: message.clone(),
            });
            return Err(Error::Provisioning { task, message });
        }
        Ok(AnsibleRolesRuntime)
    }

    async fn destroy(&self, _: &AnsibleRolesRuntime, _: &dyn DestroyContext) -> Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sak_cloud::api::context::LookupProviders;
    use std::sync::Arc;

    /// a provider that finds nothing of `L`
    struct Nothing;

    #[async_trait]
    impl<L: Lookup> LookupProvider<L> for Nothing {
        async fn lookup(&self, _: &L, _: &dyn LookupContext) -> Result<Option<L::Runtime>> {
            Ok(None)
        }
    }

    fn roles() -> AnsibleRoles {
        AnsibleRoles {
            name: "sak-alpha".to_string(),
            server: HetznerServerLookup::new("sak-alpha"),
            volume: HetznerVolumeLookup::new("sak-alpha-data"),
        }
    }

    fn provisioner(force: bool) -> AnsibleRolesProvisioner {
        AnsibleRolesProvisioner {
            run: crate::core::provision::tests::run(),
            runtime: ContainerRuntime::default(),
            force,
            events: None,
        }
    }

    fn context() -> LookupProviders {
        LookupProviders::new()
            .with::<HetznerServerLookup, _>(Arc::new(Nothing))
            .with::<HetznerVolumeLookup, _>(Arc::new(Nothing))
    }

    /// the context of a diff with one resource pending, as the registry marks it while it diffs,
    /// or applied with a change, as it marks it while it applies
    struct Pending {
        context: LookupProviders,
        pending: Option<ResourceRef>,
        applied: Option<ResourceRef>,
    }

    #[async_trait]
    impl LookupContext for Pending {
        async fn lookup_erased(
            &self,
            lookup: &dyn sak_cloud::api::AnyLookup,
        ) -> Result<Option<Box<dyn std::any::Any + Send + Sync>>> {
            self.context.lookup_erased(lookup).await
        }
    }

    impl DiffContext for Pending {
        fn has_pending_change(&self, resource: &ResourceRef) -> bool {
            self.pending.as_ref() == Some(resource)
        }

        fn applied_with_change(&self, resource: &ResourceRef) -> bool {
            self.applied.as_ref() == Some(resource)
        }
    }

    #[tokio::test]
    async fn a_server_or_volume_that_changes_runs_the_roles_without_asking_it() {
        // the plan sees it pending, the apply sees it applied with a change: both run the roles
        for dependency in [roles().server.resource_ref(), roles().volume.resource_ref()] {
            for applied in [false, true] {
                let context = Pending {
                    context: context(),
                    pending: (!applied).then(|| dependency.clone()),
                    applied: applied.then(|| dependency.clone()),
                };
                let diff = provisioner(false).diff(&roles(), &context).await.unwrap();
                assert_eq!(diff.status, DiffStatus::HasChanges);
                assert_eq!(diff.changes[0].log_text(), "server changed");
            }
        }
    }

    #[tokio::test]
    async fn without_a_server_or_a_volume_the_roles_are_missing() {
        for force in [false, true] {
            let diff = provisioner(force).diff(&roles(), &context()).await.unwrap();
            assert_eq!(diff.status, DiffStatus::Missing);
        }
    }

    #[test]
    fn the_roles_come_after_the_server_and_the_volume_and_are_named_like_them() {
        let roles = roles();
        assert_eq!(
            roles.depends_on(),
            vec![roles.server.resource_ref(), roles.volume.resource_ref()]
        );
        assert!(is_provisioning(&roles.resource_ref()));
        assert!(!is_provisioning(&roles.server.resource_ref()));
        assert!(roles.log_text().contains("sak-alpha"));
    }
}
