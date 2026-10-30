//! the provisioning of the server as a resource of the plan: the roles of the playbook on the
//! server of a deployment. It depends on the server and the data volume, so the registry plans
//! and applies it after them, as any other resource. Its diff asks the server for the markers of
//! the roles (`core::roles`): a server that changes in this run, a server without markers and a
//! role whose marker is not its fingerprint need a run. Its apply runs the playbook in the image of
//! the tools (`core::provision`), each line and task an event of the run. It has nothing to
//! destroy: the markers go with the server. sakctl declares it, sak-cloud does not know ansible

use crate::core::container::ContainerRuntime;
use crate::core::deployments::CreateEvent;
use crate::core::provision::{ProvisionManager, ProvisionOutput, ProvisionRun};
use crate::core::roles;
use crate::core::ssh::{SshTarget, Stream, run_streaming};
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
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
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
/// forced, whether the server changed in this run, where the events of the playbook go, and the
/// failure of the playbook for the error of the run
pub struct AnsibleRolesProvisioner {
    pub run: ProvisionRun,
    pub runtime: ContainerRuntime,
    pub force: bool,
    /// set by the run when it created or changed the server; the registry forgets what is pending
    /// once it applies
    pub server_changed: Arc<AtomicBool>,
    pub events: Option<UnboundedSender<CreateEvent>>,
    pub failure: Arc<Mutex<Option<String>>>,
}

impl AnsibleRolesProvisioner {
    fn send(&self, event: CreateEvent) {
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
            let code = run_streaming(
                runtime,
                &target,
                &[roles::read_command()],
                Stream::Out(&mut out),
                &mut |_| {},
            )?;
            if code != 0 {
                return Err(format!("ssh ended with exit code {code}"));
            }
            Ok(roles::parse(&String::from_utf8_lossy(&out)))
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
        let server_changes = context.has_pending_change(&resource.server.resource_ref())
            || context.has_pending_change(&resource.volume.resource_ref())
            || self.server_changed.load(Ordering::SeqCst);
        if server_changes {
            return Ok(changed(vec![DiffItem::removed("server")]));
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
        self.send(CreateEvent::Provisioning {
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
        let mut failed_task = None;
        while let Some(output) = receiver.recv().await {
            let event = match output {
                ProvisionOutput::Line(line) => Some(CreateEvent::Output { line }),
                ProvisionOutput::Event(event) => crate::core::deployments::task_event(&name, event),
            };
            if let Some(CreateEvent::TaskDone {
                task,
                outcome:
                    crate::core::deployments::TaskOutcome::Failed
                    | crate::core::deployments::TaskOutcome::Unreachable,
                ..
            }) = &event
            {
                failed_task.get_or_insert_with(|| task.clone());
            }
            if let Some(event) = event {
                self.send(event);
            }
        }
        let result = provisioning
            .await
            .map_err(|error| Error::message(error.to_string()))?;
        if let Err(message) = result {
            self.send(CreateEvent::ProvisionFailed {
                resource: name,
                task: failed_task,
                message: message.clone(),
            });
            if let Ok(mut failure) = self.failure.lock() {
                *failure = Some(message.clone());
            }
            return Err(Error::message(message));
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
            server_changed: Default::default(),
            events: None,
            failure: Default::default(),
        }
    }

    fn context() -> LookupProviders {
        LookupProviders::new()
            .with::<HetznerServerLookup, _>(Arc::new(Nothing))
            .with::<HetznerVolumeLookup, _>(Arc::new(Nothing))
    }

    #[tokio::test]
    async fn a_server_that_changes_runs_the_roles_without_asking_it() {
        let mut context = context();
        context.mark_pending(roles().server.resource_ref());
        let diff = provisioner(false).diff(&roles(), &context).await.unwrap();
        assert_eq!(diff.status, DiffStatus::HasChanges);
        assert_eq!(diff.changes[0].log_text(), "server changed");

        // the registry forgets what is pending once it applies, the run tells it
        let changed = provisioner(false);
        changed.server_changed.store(true, Ordering::SeqCst);
        let diff = changed
            .diff(&roles(), &super::tests::context())
            .await
            .unwrap();
        assert_eq!(diff.status, DiffStatus::HasChanges);
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
