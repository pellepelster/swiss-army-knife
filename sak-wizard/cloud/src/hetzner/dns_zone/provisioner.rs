use crate::api::{
    ApplyContext, DestroyContext, DiffContext, DiffItem, Listable, LookupContext, LookupProvider,
    Provisioner, Resource, ResourceDiff, Result,
};
use crate::hetzner::api::zones::{ZoneCreate, ZoneMode, ZoneUpdate};
use crate::hetzner::api::{HetznerApi, ListQuery};
use crate::hetzner::dns_zone::{HetznerDnsZone, HetznerDnsZoneLookup, HetznerDnsZoneRuntime};
use crate::hetzner::hetzner_label_diff;
use async_trait::async_trait;
use std::sync::Arc;
use tracing::info;

pub struct HetznerDnsZoneProvisioner {
    api: Arc<HetznerApi>,
}

impl HetznerDnsZoneProvisioner {
    pub fn new(api: Arc<HetznerApi>) -> Self {
        HetznerDnsZoneProvisioner { api }
    }

    async fn find(&self, name: &str) -> Result<Option<HetznerDnsZoneRuntime>> {
        Ok(self.api.zones().get(name).await?.map(Into::into))
    }
}

#[async_trait]
impl LookupProvider<HetznerDnsZoneLookup> for HetznerDnsZoneProvisioner {
    async fn lookup(
        &self,
        lookup: &HetznerDnsZoneLookup,
        _: &dyn LookupContext,
    ) -> Result<Option<HetznerDnsZoneRuntime>> {
        self.find(&lookup.name).await
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
        let Some(runtime) = self.find(&resource.name).await? else {
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
        let runtime = match self.find(&resource.name).await? {
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
    async fn destroy(&self, lookup: &HetznerDnsZoneLookup, _: &dyn DestroyContext) -> Result<bool> {
        let Some(runtime) = self.find(&lookup.name).await? else {
            return Ok(false);
        };
        let zones = self.api.zones();
        if runtime.delete_protected {
            let action = zones.change_protection(&lookup.name, false).await?;
            zones.wait_for_action(&action).await?;
        }
        match zones.delete(&lookup.name).await? {
            Some(action) => {
                zones.wait_for_action(&action).await?;
                Ok(true)
            }
            None => Ok(false),
        }
    }
}
