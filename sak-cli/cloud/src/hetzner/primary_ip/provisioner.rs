use crate::api::{DiffItem, Labeled, Resource, Result};
use crate::hetzner::api::primary_ips::{PrimaryIpCreate, PrimaryIpUpdate};
use crate::hetzner::api::{HetznerApi, ListQuery};
use crate::hetzner::kind::HetznerKind;
use crate::hetzner::primary_ip::{HetznerPrimaryIp, HetznerPrimaryIpRuntime};
use async_trait::async_trait;
use std::sync::Arc;
use tracing::info;

pub struct HetznerPrimaryIpProvisioner {
    api: Arc<HetznerApi>,
}

impl HetznerPrimaryIpProvisioner {
    pub fn new(api: Arc<HetznerApi>) -> Self {
        HetznerPrimaryIpProvisioner { api }
    }
}

#[async_trait]
impl HetznerKind for HetznerPrimaryIpProvisioner {
    type Resource = HetznerPrimaryIp;
    type Runtime = HetznerPrimaryIpRuntime;

    async fn fetch(&self, name: &str) -> Result<Option<HetznerPrimaryIpRuntime>> {
        Ok(self
            .api
            .primary_ips()
            .get_by_name(name)
            .await?
            .map(Into::into))
    }

    async fn names(&self) -> Result<Vec<String>> {
        let ips = self.api.primary_ips().list(&ListQuery::new()).await?;
        Ok(ips.into_iter().map(|ip| ip.name).collect())
    }

    fn compare(
        &self,
        resource: &HetznerPrimaryIp,
        runtime: &HetznerPrimaryIpRuntime,
    ) -> Result<Vec<DiffItem>> {
        let mut changes = Vec::new();
        if resource.ip_type != runtime.ip_type {
            changes.push(DiffItem::recreate(
                "type",
                resource.ip_type,
                runtime.ip_type,
            ));
        }
        if resource.location != runtime.location {
            changes.push(DiffItem::recreate(
                "location",
                resource.location,
                runtime.location,
            ));
        }
        if resource.protected != runtime.delete_protected {
            changes.push(DiffItem::changed(
                "delete protection",
                resource.protected,
                runtime.delete_protected,
            ));
        }
        if runtime.auto_delete {
            changes.push(DiffItem::changed("auto delete", false, true));
        }
        Ok(changes)
    }

    async fn create(&self, resource: &HetznerPrimaryIp) -> Result<HetznerPrimaryIpRuntime> {
        info!(
            "{} not found, creating ({}) in '{}'",
            resource.log_text(),
            resource.ip_type,
            resource.location
        );
        let request = PrimaryIpCreate {
            name: resource.name.clone(),
            ip_type: resource.ip_type,
            assignee_type: None,
            assignee_id: None,
            location: Some(resource.location),
            auto_delete: false,
            labels: Some(resource.labels.clone()),
        };
        Ok(self
            .api
            .primary_ips()
            .create(&request)
            .await?
            .primary_ip
            .into())
    }

    /// the delete protection, which a create cannot set
    async fn update(
        &self,
        resource: &HetznerPrimaryIp,
        runtime: &HetznerPrimaryIpRuntime,
    ) -> Result<()> {
        if runtime.delete_protected != resource.protected {
            let ips = self.api.primary_ips();
            let action = ips
                .change_protection(runtime.id, resource.protected)
                .await?;
            ips.wait_for_action(&action).await?;
        }
        Ok(())
    }

    /// the auto delete is written with the labels
    fn labels_differ(
        &self,
        resource: &HetznerPrimaryIp,
        runtime: &HetznerPrimaryIpRuntime,
    ) -> bool {
        resource.labels() != runtime.labels() || runtime.auto_delete
    }

    async fn update_labels(
        &self,
        resource: &HetznerPrimaryIp,
        runtime: &HetznerPrimaryIpRuntime,
    ) -> Result<()> {
        let request = PrimaryIpUpdate {
            name: Some(resource.name.clone()),
            labels: Some(resource.labels.clone()),
            auto_delete: Some(false),
        };
        self.api.primary_ips().update(runtime.id, &request).await?;
        Ok(())
    }

    /// removes the delete protection and unassigns the ip
    async fn prepare_destroy(&self, runtime: &HetznerPrimaryIpRuntime) -> Result<()> {
        let ips = self.api.primary_ips();
        if runtime.delete_protected {
            let action = ips.change_protection(runtime.id, false).await?;
            ips.wait_for_action(&action).await?;
        }
        if runtime.assignee_id.is_some() {
            let action = ips.unassign(runtime.id).await?;
            ips.wait_for_action(&action).await?;
        }
        Ok(())
    }

    async fn delete(&self, runtime: &HetznerPrimaryIpRuntime) -> Result<()> {
        self.api.primary_ips().delete(runtime.id).await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::{DiffStatus, LookupProviders, Provisioner};
    use crate::hetzner::api::Location;
    use crate::hetzner::test_support::{labels, mock_api};
    use serde_json::{Value, json};

    fn primary_ip(
        name: &str,
        ip_type: &str,
        location: &str,
        auto_delete: bool,
        labels: Value,
    ) -> Value {
        json!({
            "id": 11, "name": name, "ip": "1.2.3.4", "type": ip_type, "assignee_id": null, "assignee_type": "unassigned",
            "auto_delete": auto_delete, "protection": { "delete": true }, "location": { "id": 3, "name": location },
            "labels": labels
        })
    }

    async fn provisioner_with(
        ips: Vec<Value>,
    ) -> (wiremock::MockServer, HetznerPrimaryIpProvisioner) {
        let (server, api) = mock_api("primary_ips", ips).await;
        (server, HetznerPrimaryIpProvisioner::new(api))
    }

    #[tokio::test]
    async fn diff_of_a_missing_ip() {
        let (_server, provisioner) = provisioner_with(vec![]).await;

        let diff = provisioner
            .diff(
                &HetznerPrimaryIp::ipv4("backend", Location::nbg1),
                &LookupProviders::new(),
            )
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::Missing);
    }

    #[tokio::test]
    async fn diff_of_an_up_to_date_ip() {
        let (_server, provisioner) = provisioner_with(vec![primary_ip(
            "backend",
            "ipv4",
            "nbg1",
            false,
            json!({ "app": "sak" }),
        )])
        .await;
        let resource = HetznerPrimaryIp::ipv4("backend", Location::nbg1)
            .with_labels(labels(&[("app", "sak")]));

        let diff = provisioner
            .diff(&resource, &LookupProviders::new())
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::UpToDate);
    }

    #[tokio::test]
    async fn type_and_location_recreate_protection_and_auto_delete_update() {
        let (_server, provisioner) =
            provisioner_with(vec![primary_ip("backend", "ipv4", "nbg1", true, json!({}))]).await;
        let resource = HetznerPrimaryIp::ipv6("backend", Location::hel1).with_protected(false);

        let diff = provisioner
            .diff(&resource, &LookupProviders::new())
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::HasChanges);
        assert!(diff.needs_recreate());
        assert_eq!(
            diff.changes,
            vec![
                DiffItem::recreate("type", "ipv6", "ipv4"),
                DiffItem::recreate("location", "hel1", "nbg1"),
                DiffItem::changed("delete protection", "false", "true"),
                DiffItem::changed("auto delete", "false", "true"),
            ]
        );
    }

    #[tokio::test]
    async fn an_apply_writes_the_auto_delete_with_the_labels_and_nothing_when_both_are_as_declared()
    {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, ResponseTemplate};

        let resource = HetznerPrimaryIp::ipv4("backend", Location::nbg1)
            .with_labels(labels(&[("app", "sak")]));
        for (auto_delete, writes) in [(false, 0), (true, 1)] {
            let live = primary_ip(
                "backend",
                "ipv4",
                "nbg1",
                auto_delete,
                json!({ "app": "sak" }),
            );
            let (server, provisioner) = provisioner_with(vec![live.clone()]).await;
            Mock::given(method("PUT"))
                .and(path("/primary_ips/11"))
                .respond_with(
                    ResponseTemplate::new(200).set_body_json(json!({ "primary_ip": live })),
                )
                .expect(writes)
                .mount(&server)
                .await;

            provisioner
                .apply(&resource, &LookupProviders::new())
                .await
                .unwrap();
        }
    }
}
