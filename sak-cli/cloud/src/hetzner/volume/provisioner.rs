use crate::api::Lookup as _;
use crate::api::identity;
use crate::api::{
    ApplyContext, DestroyContext, DiffContext, DiffItem, Listable, LookupContext, LookupProvider,
    Provisioner, Resource, ResourceDiff, Result,
};
use crate::hetzner::api::volumes::{VolumeCreate, VolumeUpdate};
use crate::hetzner::api::{HetznerApi, ListQuery};
use crate::hetzner::hetzner_label_diff;
use crate::hetzner::volume::{HetznerVolume, HetznerVolumeLookup, HetznerVolumeRuntime};
use async_trait::async_trait;
use std::sync::Arc;
use tracing::info;

pub struct HetznerVolumeProvisioner {
    api: Arc<HetznerApi>,
}

impl HetznerVolumeProvisioner {
    pub fn new(api: Arc<HetznerApi>) -> Self {
        HetznerVolumeProvisioner { api }
    }

    /// the resource of the lookup's name, refused (`NotOwned`) when it lacks the identity labels
    /// of the lookup
    async fn find(&self, lookup: &HetznerVolumeLookup) -> Result<Option<HetznerVolumeRuntime>> {
        let found: Result<Option<HetznerVolumeRuntime>> = {
            Ok(self
                .api
                .volumes()
                .get_by_name(&lookup.name)
                .await?
                .map(Into::into))
        };
        identity::check(found?, lookup.identity(), &lookup.log_text())
    }
}

#[async_trait]
impl LookupProvider<HetznerVolumeLookup> for HetznerVolumeProvisioner {
    async fn lookup(
        &self,
        lookup: &HetznerVolumeLookup,
        _: &dyn LookupContext,
    ) -> Result<Option<HetznerVolumeRuntime>> {
        self.find(lookup).await
    }
}

#[async_trait]
impl Listable<HetznerVolumeLookup> for HetznerVolumeProvisioner {
    async fn list(&self) -> Result<Vec<HetznerVolumeLookup>> {
        Ok(self
            .api
            .volumes()
            .list(&ListQuery::new())
            .await?
            .iter()
            .map(|volume| HetznerVolumeLookup::new(&volume.name))
            .collect())
    }
}

#[async_trait]
impl Provisioner<HetznerVolume> for HetznerVolumeProvisioner {
    async fn diff(&self, resource: &HetznerVolume, _: &dyn DiffContext) -> Result<ResourceDiff> {
        let Some(runtime) = self.find(&resource.as_lookup()).await? else {
            return Ok(ResourceDiff::missing(resource));
        };

        let mut changes = Vec::new();
        if resource.location != runtime.location {
            changes.push(DiffItem::recreate(
                "location",
                resource.location,
                runtime.location,
            ));
        }
        if resource.size_gb != runtime.size_gb {
            changes.push(DiffItem::recreate(
                "size",
                resource.size_gb,
                runtime.size_gb,
            ));
        }
        if resource.protected != runtime.delete_protected {
            changes.push(DiffItem::changed(
                "delete protection",
                resource.protected,
                runtime.delete_protected,
            ));
        }
        changes.extend(hetzner_label_diff(resource, &runtime));

        Ok(ResourceDiff::from_changes(resource, changes))
    }

    async fn apply(
        &self,
        resource: &HetznerVolume,
        _: &dyn ApplyContext,
    ) -> Result<HetznerVolumeRuntime> {
        let runtime = match self.find(&resource.as_lookup()).await? {
            Some(runtime) => runtime,
            None => {
                info!(
                    "{} not found, creating with {} GB",
                    resource.log_text(),
                    resource.size_gb
                );
                let request = VolumeCreate {
                    name: resource.name.clone(),
                    size: resource.size_gb,
                    location: resource.location,
                    format: resource.format,
                    automount: false,
                    labels: Some(resource.labels.clone()),
                };
                self.api.volumes().create(&request).await?.into()
            }
        };

        if runtime.delete_protected != resource.protected {
            let action = self
                .api
                .volumes()
                .change_protection(runtime.id, resource.protected)
                .await?;
            self.api.volumes().wait_for_action(&action).await?;
        }

        let request = VolumeUpdate {
            name: Some(resource.name.clone()),
            labels: Some(resource.labels.clone()),
        };
        Ok(self
            .api
            .volumes()
            .update(runtime.id, &request)
            .await?
            .into())
    }

    /// lifts the delete protection and detaches the volume first
    async fn destroy(&self, runtime: &HetznerVolumeRuntime, _: &dyn DestroyContext) -> Result<()> {
        if runtime.delete_protected {
            let action = self
                .api
                .volumes()
                .change_protection(runtime.id, false)
                .await?;
            self.api.volumes().wait_for_action(&action).await?;
        }
        if runtime.server.is_some() {
            let action = self.api.volumes().detach(runtime.id).await?;
            self.api.volumes().wait_for_action(&action).await?;
        }
        self.api.volumes().delete(runtime.id).await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::{DiffStatus, LookupProviders};
    use crate::hetzner::api::Location;
    use crate::hetzner::test_support::{labels, mock_api};
    use serde_json::{Value, json};

    fn volume(name: &str, size: u64, protected: bool, labels: Value) -> Value {
        json!({
            "id": 3, "name": name, "format": "ext4", "linux_device": "/dev/disk/by-id/scsi-0HC_Volume_3", "size": size,
            "protection": { "delete": protected }, "server": null, "status": "available",
            "location": { "id": 2, "name": "nbg1" }, "labels": labels
        })
    }

    async fn provisioner_with(
        volumes: Vec<Value>,
    ) -> (wiremock::MockServer, HetznerVolumeProvisioner) {
        let (server, api) = mock_api("volumes", volumes).await;
        (server, HetznerVolumeProvisioner::new(api))
    }

    #[tokio::test]
    async fn diff_of_a_missing_volume() {
        let (_server, provisioner) = provisioner_with(vec![]).await;

        let diff = provisioner
            .diff(
                &HetznerVolume::new("data", Location::nbg1, 16),
                &LookupProviders::new(),
            )
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::Missing);
    }

    #[tokio::test]
    async fn diff_of_an_up_to_date_volume() {
        let (_server, provisioner) =
            provisioner_with(vec![volume("data", 16, true, json!({ "app": "sak" }))]).await;
        let resource =
            HetznerVolume::new("data", Location::nbg1, 16).with_labels(labels(&[("app", "sak")]));

        let diff = provisioner
            .diff(&resource, &LookupProviders::new())
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::UpToDate);
    }

    #[tokio::test]
    async fn size_and_location_recreate_protection_and_labels_update() {
        let (_server, provisioner) =
            provisioner_with(vec![volume("data", 16, true, json!({}))]).await;
        let resource = HetznerVolume::new("data", Location::hel1, 32)
            .with_protected(false)
            .with_labels(labels(&[("app", "sak")]));

        let diff = provisioner
            .diff(&resource, &LookupProviders::new())
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::HasChanges);
        assert!(diff.needs_recreate());
        assert_eq!(
            diff.changes,
            vec![
                DiffItem::recreate("location", "hel1", "nbg1"),
                DiffItem::recreate("size", "32", "16"),
                DiffItem::changed("delete protection", "false", "true"),
                DiffItem::missing("label 'app'"),
            ]
        );
    }
}
