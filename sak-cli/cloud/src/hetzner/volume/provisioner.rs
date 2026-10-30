use crate::api::{DiffItem, Resource, Result};
use crate::hetzner::api::volumes::{VolumeCreate, VolumeUpdate};
use crate::hetzner::api::{HetznerApi, ListQuery};
use crate::hetzner::kind::HetznerKind;
use crate::hetzner::volume::{HetznerVolume, HetznerVolumeRuntime};
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
}

#[async_trait]
impl HetznerKind for HetznerVolumeProvisioner {
    type Resource = HetznerVolume;
    type Runtime = HetznerVolumeRuntime;

    async fn fetch(&self, name: &str) -> Result<Option<HetznerVolumeRuntime>> {
        Ok(self.api.volumes().get_by_name(name).await?.map(Into::into))
    }

    async fn names(&self) -> Result<Vec<String>> {
        let volumes = self.api.volumes().list(&ListQuery::new()).await?;
        Ok(volumes.into_iter().map(|volume| volume.name).collect())
    }

    fn compare(
        &self,
        resource: &HetznerVolume,
        runtime: &HetznerVolumeRuntime,
    ) -> Result<Vec<DiffItem>> {
        let mut changes = Vec::new();
        if resource.location != runtime.location {
            changes.push(DiffItem::recreate(
                "location",
                resource.location,
                runtime.location,
            ));
        }
        // a volume only grows: a volume larger than declared (e.g. grown in the console) is up
        // to date, a smaller volume needs a recreate
        if resource.size_gb > runtime.size_gb {
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
        Ok(changes)
    }

    async fn create(&self, resource: &HetznerVolume) -> Result<HetznerVolumeRuntime> {
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
        Ok(self.api.volumes().create(&request).await?.into())
    }

    /// the delete protection, which a create cannot set
    async fn update(&self, resource: &HetznerVolume, runtime: &HetznerVolumeRuntime) -> Result<()> {
        if runtime.delete_protected != resource.protected {
            let volumes = self.api.volumes();
            let action = volumes
                .change_protection(runtime.id, resource.protected)
                .await?;
            volumes.wait_for_action(&action).await?;
        }
        Ok(())
    }

    async fn update_labels(
        &self,
        resource: &HetznerVolume,
        runtime: &HetznerVolumeRuntime,
    ) -> Result<()> {
        let request = VolumeUpdate {
            name: Some(resource.name.clone()),
            labels: Some(resource.labels.clone()),
        };
        self.api.volumes().update(runtime.id, &request).await?;
        Ok(())
    }

    /// removes the delete protection and detaches the volume
    async fn prepare_destroy(&self, runtime: &HetznerVolumeRuntime) -> Result<()> {
        let volumes = self.api.volumes();
        if runtime.delete_protected {
            let action = volumes.change_protection(runtime.id, false).await?;
            volumes.wait_for_action(&action).await?;
        }
        if runtime.server.is_some() {
            let action = volumes.detach(runtime.id).await?;
            volumes.wait_for_action(&action).await?;
        }
        Ok(())
    }

    async fn delete(&self, runtime: &HetznerVolumeRuntime) -> Result<()> {
        self.api.volumes().delete(runtime.id).await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::{DiffStatus, LookupProviders, Provisioner};
    use crate::hetzner::api::Location;
    use crate::hetzner::test_support::{labels, mock_api, page};
    use serde_json::{Value, json};
    use wiremock::matchers::{method, path, query_param};
    use wiremock::{Mock, ResponseTemplate};

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
    async fn another_or_no_version_is_no_diff_a_declared_label_is() {
        use crate::hetzner::{NAME_LABEL, VERSION_LABEL};
        let resource = HetznerVolume::new("data", Location::nbg1, 16)
            .with_labels(labels(&[(NAME_LABEL, "alpha")]))
            .with_info(labels(&[(VERSION_LABEL, "0.1.2")]));
        for live in [
            json!({ NAME_LABEL: "alpha", VERSION_LABEL: "0.1.1" }),
            json!({ NAME_LABEL: "alpha" }),
        ] {
            let (_server, provisioner) =
                provisioner_with(vec![volume("data", 16, true, live)]).await;
            let diff = provisioner
                .diff(&resource, &LookupProviders::new())
                .await
                .unwrap();
            assert_eq!(diff.status, DiffStatus::UpToDate, "{:?}", diff.changes);
        }

        let (_server, provisioner) = provisioner_with(vec![volume(
            "data",
            16,
            true,
            json!({ NAME_LABEL: "beta", VERSION_LABEL: "0.1.1" }),
        )])
        .await;
        let diff = provisioner
            .diff(&resource, &LookupProviders::new())
            .await
            .unwrap();
        assert_eq!(
            diff.changes,
            vec![DiffItem::changed(
                format!("label '{NAME_LABEL}'"),
                "alpha",
                "beta"
            )]
        );
    }

    #[tokio::test]
    async fn a_volume_grown_beyond_its_declared_size_is_up_to_date() {
        // grown in the console from the declared 16 to 32 GB; a volume does not shrink
        let (_server, provisioner) =
            provisioner_with(vec![volume("data", 32, true, json!({}))]).await;

        let diff = provisioner
            .diff(
                &HetznerVolume::new("data", Location::nbg1, 16),
                &LookupProviders::new(),
            )
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::UpToDate, "{:?}", diff.changes);
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

    /// a mock of the update of volume 3, expected `times` times
    async fn expect_update(server: &wiremock::MockServer, times: u64) {
        Mock::given(method("PUT"))
            .and(path("/volumes/3"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(json!({ "volume": volume("data", 16, true, json!({})) })),
            )
            .expect(times)
            .mount(server)
            .await;
    }

    #[tokio::test]
    async fn an_apply_writes_the_labels_only_when_they_differ() {
        let resource =
            HetznerVolume::new("data", Location::nbg1, 16).with_labels(labels(&[("app", "sak")]));
        for (live, writes) in [(json!({ "app": "sak" }), 0), (json!({ "app": "old" }), 1)] {
            let (server, provisioner) =
                provisioner_with(vec![volume("data", 16, true, live)]).await;
            expect_update(&server, writes).await;

            provisioner
                .apply(&resource, &LookupProviders::new())
                .await
                .unwrap();
        }
    }

    #[tokio::test]
    async fn a_created_volume_is_not_written_again() {
        let (server, provisioner) = provisioner_with(vec![]).await;
        let created = volume("data", 16, false, json!({ "app": "sak" }));
        // missing before the create, found after it
        Mock::given(method("GET"))
            .and(path("/volumes"))
            .and(query_param("name", "data"))
            .respond_with(ResponseTemplate::new(200).set_body_json(page("volumes", vec![])))
            .up_to_n_times(1)
            .with_priority(1)
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/volumes"))
            .and(query_param("name", "data"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(page("volumes", vec![created.clone()])),
            )
            .with_priority(2)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/volumes"))
            .respond_with(ResponseTemplate::new(201).set_body_json(json!({ "volume": created })))
            .expect(1)
            .mount(&server)
            .await;
        expect_update(&server, 0).await;
        let resource = HetznerVolume::new("data", Location::nbg1, 16)
            .with_protected(false)
            .with_labels(labels(&[("app", "sak")]));

        let runtime = provisioner
            .apply(&resource, &LookupProviders::new())
            .await
            .unwrap();

        assert_eq!(runtime.labels, labels(&[("app", "sak")]));
    }
}
