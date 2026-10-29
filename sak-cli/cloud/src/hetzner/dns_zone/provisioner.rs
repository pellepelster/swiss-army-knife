use crate::api::Lookup as _;
use crate::api::identity;
use crate::api::{
    ApplyContext, DestroyContext, DiffContext, DiffItem, Listable, LookupContext, LookupProvider,
    Provisioner, Resource, ResourceDiff, Result,
};
use crate::hetzner::api::zones::{RRSet, ZoneCreate, ZoneMode, ZoneUpdate};
use crate::hetzner::api::{HetznerApi, ListQuery};
use crate::hetzner::dns_zone::{HetznerDnsZone, HetznerDnsZoneLookup, HetznerDnsZoneRuntime};
use crate::hetzner::hetzner_label_diff;
use async_trait::async_trait;
use std::sync::Arc;
use tracing::info;

pub struct HetznerDnsZoneProvisioner {
    api: Arc<HetznerApi>,
}

/// a zone of the project with its rrsets: what a deployment finds of the domains it can
/// use, of the deployments that use them already and of its own records
#[derive(Clone, Debug)]
pub struct HetznerDnsZoneListing {
    pub zone: HetznerDnsZoneRuntime,
    /// every rrset of the zone, unlabeled ones included
    pub rrsets: Vec<RRSet>,
}

impl HetznerDnsZoneProvisioner {
    pub fn new(api: Arc<HetznerApi>) -> Self {
        HetznerDnsZoneProvisioner { api }
    }

    /// the resource of the lookup's name, refused (`NotOwned`) when it lacks the identity labels
    /// of the lookup
    async fn find(&self, lookup: &HetznerDnsZoneLookup) -> Result<Option<HetznerDnsZoneRuntime>> {
        let found: Result<Option<HetznerDnsZoneRuntime>> =
            { Ok(self.api.zones().get(&lookup.name).await?.map(Into::into)) };
        identity::check(found?, lookup.identity(), &lookup.log_text())
    }

    /// every zone of the project with its rrsets, by the name of the zone
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

#[async_trait]
impl LookupProvider<HetznerDnsZoneLookup> for HetznerDnsZoneProvisioner {
    async fn lookup(
        &self,
        lookup: &HetznerDnsZoneLookup,
        _: &dyn LookupContext,
    ) -> Result<Option<HetznerDnsZoneRuntime>> {
        self.find(lookup).await
    }
}

#[async_trait]
impl Listable<HetznerDnsZoneLookup> for HetznerDnsZoneProvisioner {
    async fn list(&self) -> Result<Vec<HetznerDnsZoneLookup>> {
        Ok(self
            .api
            .zones()
            .list(&ListQuery::new())
            .await?
            .iter()
            .map(|zone| HetznerDnsZoneLookup::new(&zone.name))
            .collect())
    }
}

#[async_trait]
impl Provisioner<HetznerDnsZone> for HetznerDnsZoneProvisioner {
    async fn diff(&self, resource: &HetznerDnsZone, _: &dyn DiffContext) -> Result<ResourceDiff> {
        let Some(runtime) = self.find(&resource.as_lookup()).await? else {
            return Ok(ResourceDiff::missing(resource));
        };

        let mut changes = Vec::new();
        if let Some(ttl) = resource.ttl
            && ttl != runtime.ttl
        {
            changes.push(DiffItem::changed("ttl", ttl, runtime.ttl));
        }
        changes.extend(hetzner_label_diff(resource, &runtime));

        Ok(ResourceDiff::from_changes(resource, changes))
    }

    async fn apply(
        &self,
        resource: &HetznerDnsZone,
        _: &dyn ApplyContext,
    ) -> Result<HetznerDnsZoneRuntime> {
        let zones = self.api.zones();
        let runtime = match self.find(&resource.as_lookup()).await? {
            Some(runtime) => runtime,
            None => {
                info!("{} not found, creating", resource.log_text());
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
                created.zone.into()
            }
        };

        if let Some(ttl) = resource.ttl
            && ttl != runtime.ttl
        {
            let action = zones.change_ttl(&resource.name, ttl).await?;
            zones.wait_for_action(&action).await?;
        }

        let request = ZoneUpdate {
            labels: Some(resource.labels.clone()),
        };
        Ok(zones.update(&resource.name, &request).await?.into())
    }

    /// deletes the zone with every rrset in it
    async fn destroy(&self, runtime: &HetznerDnsZoneRuntime, _: &dyn DestroyContext) -> Result<()> {
        let zones = self.api.zones();
        if runtime.delete_protected {
            let action = zones.change_protection(&runtime.name, false).await?;
            zones.wait_for_action(&action).await?;
        }
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

    /// a mock of the project: `zones` each with its rrsets. The server must stay alive with
    /// the api
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
