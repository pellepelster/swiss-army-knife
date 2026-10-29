use crate::api::Lookup as _;
use crate::api::identity;
use crate::api::{
    ApplyContext, DestroyContext, DiffContext, DiffItem, Listable, LookupContext, LookupProvider,
    Provisioner, Resource, ResourceDiff, Result,
};
use crate::hetzner::api::primary_ips::{PrimaryIpCreate, PrimaryIpUpdate};
use crate::hetzner::api::{HetznerApi, ListQuery};
use crate::hetzner::hetzner_label_diff;
use crate::hetzner::primary_ip::{
    HetznerPrimaryIp, HetznerPrimaryIpLookup, HetznerPrimaryIpRuntime,
};
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

    /// the resource of the lookup's name, refused (`NotOwned`) when it lacks the identity labels
    /// of the lookup
    async fn find(
        &self,
        lookup: &HetznerPrimaryIpLookup,
    ) -> Result<Option<HetznerPrimaryIpRuntime>> {
        let found: Result<Option<HetznerPrimaryIpRuntime>> = {
            Ok(self
                .api
                .primary_ips()
                .get_by_name(&lookup.name)
                .await?
                .map(Into::into))
        };
        identity::check(found?, lookup.identity(), &lookup.log_text())
    }
}

#[async_trait]
impl LookupProvider<HetznerPrimaryIpLookup> for HetznerPrimaryIpProvisioner {
    async fn lookup(
        &self,
        lookup: &HetznerPrimaryIpLookup,
        _: &dyn LookupContext,
    ) -> Result<Option<HetznerPrimaryIpRuntime>> {
        self.find(lookup).await
    }
}

#[async_trait]
impl Listable<HetznerPrimaryIpLookup> for HetznerPrimaryIpProvisioner {
    async fn list(&self) -> Result<Vec<HetznerPrimaryIpLookup>> {
        Ok(self
            .api
            .primary_ips()
            .list(&ListQuery::new())
            .await?
            .iter()
            .map(|ip| HetznerPrimaryIpLookup::new(&ip.name))
            .collect())
    }
}

#[async_trait]
impl Provisioner<HetznerPrimaryIp> for HetznerPrimaryIpProvisioner {
    async fn diff(&self, resource: &HetznerPrimaryIp, _: &dyn DiffContext) -> Result<ResourceDiff> {
        let Some(runtime) = self.find(&resource.as_lookup()).await? else {
            return Ok(ResourceDiff::missing(resource));
        };

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
        changes.extend(hetzner_label_diff(resource, &runtime));

        Ok(ResourceDiff::from_changes(resource, changes))
    }

    async fn apply(
        &self,
        resource: &HetznerPrimaryIp,
        _: &dyn ApplyContext,
    ) -> Result<HetznerPrimaryIpRuntime> {
        let runtime = match self.find(&resource.as_lookup()).await? {
            Some(runtime) => runtime,
            None => {
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
                self.api
                    .primary_ips()
                    .create(&request)
                    .await?
                    .primary_ip
                    .into()
            }
        };

        if runtime.delete_protected != resource.protected {
            let action = self
                .api
                .primary_ips()
                .change_protection(runtime.id, resource.protected)
                .await?;
            self.api.primary_ips().wait_for_action(&action).await?;
        }

        let request = PrimaryIpUpdate {
            name: Some(resource.name.clone()),
            labels: Some(resource.labels.clone()),
            auto_delete: Some(false),
        };
        Ok(self
            .api
            .primary_ips()
            .update(runtime.id, &request)
            .await?
            .into())
    }

    /// lifts the delete protection and unassigns the ip first
    async fn destroy(
        &self,
        runtime: &HetznerPrimaryIpRuntime,
        _: &dyn DestroyContext,
    ) -> Result<()> {
        if runtime.delete_protected {
            let action = self
                .api
                .primary_ips()
                .change_protection(runtime.id, false)
                .await?;
            self.api.primary_ips().wait_for_action(&action).await?;
        }
        if runtime.assignee_id.is_some() {
            let action = self.api.primary_ips().unassign(runtime.id).await?;
            self.api.primary_ips().wait_for_action(&action).await?;
        }
        self.api.primary_ips().delete(runtime.id).await?;
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
}
