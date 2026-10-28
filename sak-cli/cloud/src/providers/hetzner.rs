use crate::api::Registry;
use crate::api::error::{Error, Result};
use crate::hetzner::api::{HetznerApi, ListQuery, Location, ServerType};
use crate::hetzner::dns_record::{HetznerDnsRecord, HetznerDnsRecordProvisioner};
use crate::hetzner::dns_zone::{HetznerDnsZone, HetznerDnsZoneProvisioner};
use crate::hetzner::firewall::{HetznerFirewall, HetznerFirewallProvisioner};
use crate::hetzner::primary_ip::{HetznerPrimaryIp, HetznerPrimaryIpProvisioner};
use crate::hetzner::server::{HetznerServer, HetznerServerProvisioner};
use crate::hetzner::ssh_key::{HetznerSshKey, HetznerSshKeyProvisioner};
use crate::hetzner::volume::{HetznerVolume, HetznerVolumeProvisioner};
use crate::providers::{ProviderConfiguration, ProviderManager, ProviderRuntime};
use async_trait::async_trait;
use std::sync::Arc;

pub const HETZNER_PROVIDER_TYPE: &str = "hcloud";

/// a hetzner cloud project, reached with an api token of it
#[derive(Clone, Debug)]
pub struct HetznerProviderConfiguration {
    pub name: String,
    pub token: String,
    pub default_location: Location,
    pub default_server_type: ServerType,
}

impl HetznerProviderConfiguration {
    pub fn new(name: &str, token: &str) -> Self {
        HetznerProviderConfiguration {
            name: name.to_string(),
            token: token.to_string(),
            default_location: Location::nbg1,
            default_server_type: ServerType::cx23,
        }
    }

    pub fn with_defaults(mut self, location: Location, server_type: ServerType) -> Self {
        self.default_location = location;
        self.default_server_type = server_type;
        self
    }
}

impl ProviderConfiguration for HetznerProviderConfiguration {
    fn provider_type(&self) -> &'static str {
        HETZNER_PROVIDER_TYPE
    }

    fn name(&self) -> &str {
        &self.name
    }
}

/// the validated configuration with its api client; the provisioners are all built on it
pub struct HetznerProviderRuntime {
    pub api: Arc<HetznerApi>,
    pub default_location: Location,
    pub default_server_type: ServerType,
}

impl HetznerProviderRuntime {
    pub fn ssh_keys(&self) -> Arc<HetznerSshKeyProvisioner> {
        Arc::new(HetznerSshKeyProvisioner::new(self.api.clone()))
    }

    pub fn volumes(&self) -> Arc<HetznerVolumeProvisioner> {
        Arc::new(HetznerVolumeProvisioner::new(self.api.clone()))
    }

    pub fn primary_ips(&self) -> Arc<HetznerPrimaryIpProvisioner> {
        Arc::new(HetznerPrimaryIpProvisioner::new(self.api.clone()))
    }

    pub fn firewalls(&self) -> Arc<HetznerFirewallProvisioner> {
        Arc::new(HetznerFirewallProvisioner::new(self.api.clone()))
    }

    pub fn servers(&self) -> Arc<HetznerServerProvisioner> {
        Arc::new(HetznerServerProvisioner::new(self.api.clone()))
    }

    pub fn dns_zones(&self) -> Arc<HetznerDnsZoneProvisioner> {
        Arc::new(HetznerDnsZoneProvisioner::new(self.api.clone()))
    }

    pub fn dns_records(&self) -> Arc<HetznerDnsRecordProvisioner> {
        Arc::new(HetznerDnsRecordProvisioner::new(self.api.clone()))
    }

    /// a registry with every hetzner provisioner registered
    pub fn registry(&self) -> Registry {
        let mut registry = Registry::new();
        self.register(&mut registry);
        registry
    }
}

impl ProviderRuntime for HetznerProviderRuntime {
    fn register(&self, registry: &mut Registry) {
        registry.register::<HetznerSshKey, _>(self.ssh_keys());
        registry.register::<HetznerVolume, _>(self.volumes());
        registry.register::<HetznerPrimaryIp, _>(self.primary_ips());
        registry.register::<HetznerFirewall, _>(self.firewalls());
        registry.register::<HetznerServer, _>(self.servers());
        registry.register::<HetznerDnsZone, _>(self.dns_zones());
        registry.register::<HetznerDnsRecord, _>(self.dns_records());
    }
}

pub struct HetznerProviderManager;

#[async_trait]
impl ProviderManager<HetznerProviderConfiguration> for HetznerProviderManager {
    type Runtime = HetznerProviderRuntime;

    /// the token must read the project: a list of its ssh keys proves it
    async fn validate(
        &self,
        configuration: &HetznerProviderConfiguration,
    ) -> Result<HetznerProviderRuntime> {
        let api = Arc::new(HetznerApi::new(&configuration.token));
        api.ssh_keys()
            .list(&ListQuery::new())
            .await
            .map_err(|error| {
                Error::message(format!(
                    "hetzner provider '{}': {error}",
                    configuration.name
                ))
            })?;
        Ok(HetznerProviderRuntime {
            api,
            default_location: configuration.default_location,
            default_server_type: configuration.default_server_type,
        })
    }
}

impl HetznerProviderRuntime {
    /// a runtime from an api client already in hand, nothing is validated
    pub fn from_api(
        api: Arc<HetznerApi>,
        default_location: Location,
        default_server_type: ServerType,
    ) -> Self {
        HetznerProviderRuntime {
            api,
            default_location,
            default_server_type,
        }
    }
}
