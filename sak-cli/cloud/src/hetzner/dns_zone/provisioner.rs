use crate::api::{DiffItem, Resource, Result};
use crate::hetzner::api::zones::{RRSet, ZoneCreate, ZoneMode, ZoneUpdate};
use crate::hetzner::api::{HetznerApi, ListQuery};
use crate::hetzner::dns_zone::{HetznerDnsZone, HetznerDnsZoneRuntime};
use crate::hetzner::kind::HetznerKind;
use async_trait::async_trait;
use std::sync::Arc;
use tracing::info;

pub struct HetznerDnsZoneProvisioner {
    api: Arc<HetznerApi>,
}

/// a zone of the project with its rrsets. From it sakctl finds the domains it can use, the
/// deployments that use them and the records of a deployment
#[derive(Clone, Debug)]
pub struct HetznerDnsZoneListing {
    pub zone: HetznerDnsZoneRuntime,
    /// every rrset of the zone, also the rrsets without labels
    pub rrsets: Vec<RRSet>,
}

impl HetznerDnsZoneProvisioner {
    pub fn new(api: Arc<HetznerApi>) -> Self {
        HetznerDnsZoneProvisioner { api }
    }

    /// every zone of the project with its rrsets, sorted by the name of the zone
    pub async fn list_all(&self) -> Result<Vec<HetznerDnsZoneListing>> {
        let zones = self.api.zones();
        let mut listings = Vec::new();
        for zone in zones.list(&ListQuery::new()).await? {
            let rrsets = zones.rrsets(&zone.name).list(&ListQuery::new()).await?;
            listings.push(HetznerDnsZoneListing {
                zone: zone.into(),
                rrsets,
            });
        }
        listings.sort_by(|a, b| a.zone.name.cmp(&b.zone.name));
        Ok(listings)
    }
}

/// a zone lookup has no identity labels, so any zone of the name is the zone
#[async_trait]
impl HetznerKind for HetznerDnsZoneProvisioner {
    type Resource = HetznerDnsZone;
    type Runtime = HetznerDnsZoneRuntime;

    async fn fetch(&self, name: &str) -> Result<Option<HetznerDnsZoneRuntime>> {
        Ok(self.api.zones().get(name).await?.map(Into::into))
    }

    async fn names(&self) -> Result<Vec<String>> {
        let zones = self.api.zones().list(&ListQuery::new()).await?;
        Ok(zones.into_iter().map(|zone| zone.name).collect())
    }

    fn compare(
        &self,
        resource: &HetznerDnsZone,
        runtime: &HetznerDnsZoneRuntime,
    ) -> Result<Vec<DiffItem>> {
        let mut changes = Vec::new();
        if let Some(ttl) = resource.ttl
            && ttl != runtime.ttl
        {
            changes.push(DiffItem::changed("ttl", ttl, runtime.ttl));
        }
        Ok(changes)
    }

    async fn create(&self, resource: &HetznerDnsZone) -> Result<HetznerDnsZoneRuntime> {
        info!("{} not found, creating", resource.log_text());
        let zones = self.api.zones();
        let request = ZoneCreate {
            name: resource.name.clone(),
            mode: ZoneMode::Primary,
            ttl: resource.ttl,
            labels: Some(resource.labels.clone()),
        };
        let created = zones.create(&request).await?;
        if let Some(action) = &created.action {
            zones.wait_for_action(action).await?;
        }
        Ok(created.zone.into())
    }

    /// the default ttl, when one is declared
    async fn update(
        &self,
        resource: &HetznerDnsZone,
        runtime: &HetznerDnsZoneRuntime,
    ) -> Result<()> {
        if let Some(ttl) = resource.ttl
            && ttl != runtime.ttl
        {
            let zones = self.api.zones();
            let action = zones.change_ttl(&resource.name, ttl).await?;
            zones.wait_for_action(&action).await?;
        }
        Ok(())
    }

    /// a zone has no other name: only the labels
    async fn update_labels(
        &self,
        resource: &HetznerDnsZone,
        _: &HetznerDnsZoneRuntime,
    ) -> Result<()> {
        let request = ZoneUpdate {
            labels: Some(resource.labels.clone()),
        };
        self.api.zones().update(&resource.name, &request).await?;
        Ok(())
    }

    /// removes the delete protection
    async fn prepare_destroy(&self, runtime: &HetznerDnsZoneRuntime) -> Result<()> {
        if runtime.delete_protected {
            let zones = self.api.zones();
            let action = zones.change_protection(&runtime.name, false).await?;
            zones.wait_for_action(&action).await?;
        }
        Ok(())
    }

    /// deletes the zone with every rrset in it
    async fn delete(&self, runtime: &HetznerDnsZoneRuntime) -> Result<()> {
        let zones = self.api.zones();
        if let Some(action) = zones.delete(&runtime.name).await? {
            zones.wait_for_action(&action).await?;
        }
        Ok(())
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::hetzner::api::Backoff;
    use crate::hetzner::test_support::page;
    use serde_json::{Value, json};
    use std::time::Duration;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    pub(crate) fn zone(id: u64, name: &str, labels: Value) -> Value {
        json!({ "id": id, "name": name, "mode": "primary", "ttl": 3600, "protection": { "delete": false }, "status": "ok", "labels": labels })
    }

    pub(crate) fn rrset(zone: u64, name: &str, labels: Value) -> Value {
        json!({ "id": format!("{name}/A"), "name": name, "type": "A", "ttl": null, "protection": { "change": false }, "records": [{ "value": "1.2.3.4" }], "zone": zone, "labels": labels })
    }

    /// a mock of the project: `zones`, each with its rrsets. Keep the server while the api is
    /// used
    pub(crate) async fn mock_zones(zones: &[(Value, Vec<Value>)]) -> (MockServer, Arc<HetznerApi>) {
        let server = MockServer::start().await;
        let all: Vec<Value> = zones.iter().map(|(zone, _)| zone.clone()).collect();
        Mock::given(method("GET"))
            .and(path("/zones"))
            .respond_with(ResponseTemplate::new(200).set_body_json(page("zones", all)))
            .mount(&server)
            .await;
        for (zone, rrsets) in zones {
            let endpoint = format!("/zones/{}/rrsets", zone["name"].as_str().unwrap());
            Mock::given(method("GET"))
                .and(path(endpoint))
                .respond_with(
                    ResponseTemplate::new(200).set_body_json(page("rrsets", rrsets.clone())),
                )
                .mount(&server)
                .await;
        }
        let api = HetznerApi::with_base_url("token", &server.uri()).with_retry(Backoff::new(
            1,
            Duration::ZERO,
            Duration::ZERO,
        ));
        (server, Arc::new(api))
    }

    #[tokio::test]
    async fn list_all_answers_every_zone_with_the_labels_of_its_rrsets() {
        let (_server, api) = mock_zones(&[
            (
                zone(2, "pelle.io", json!({})),
                vec![
                    rrset(2, "@", json!({})),
                    rrset(2, "alpha", json!({ "sak.pelle.io/name": "alpha" })),
                ],
            ),
            (zone(1, "example.com", json!({ "env": "test" })), vec![]),
        ])
        .await;

        let listings = HetznerDnsZoneProvisioner::new(api)
            .list_all()
            .await
            .unwrap();

        let names: Vec<&str> = listings
            .iter()
            .map(|listing| listing.zone.name.as_str())
            .collect();
        assert_eq!(names, vec!["example.com", "pelle.io"]);
        assert_eq!(listings[0].zone.labels["env"], "test");
        assert!(listings[0].rrsets.is_empty());
        assert_eq!(listings[1].rrsets.len(), 2);
        assert_eq!(listings[1].rrsets[1].name, "alpha");
        assert_eq!(listings[1].rrsets[1].labels["sak.pelle.io/name"], "alpha");
    }
}
