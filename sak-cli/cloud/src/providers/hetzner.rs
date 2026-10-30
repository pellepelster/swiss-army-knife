use crate::api::Registry;
use crate::hetzner::api::HetznerApi;
use crate::hetzner::dns_record::{HetznerDnsRecord, HetznerDnsRecordProvisioner};
use crate::hetzner::dns_zone::{HetznerDnsZone, HetznerDnsZoneProvisioner};
use crate::hetzner::firewall::{HetznerFirewall, HetznerFirewallProvisioner};
use crate::hetzner::primary_ip::{HetznerPrimaryIp, HetznerPrimaryIpProvisioner};
use crate::hetzner::server::{HetznerServer, HetznerServerProvisioner};
use crate::hetzner::ssh_key::{HetznerSshKey, HetznerSshKeyProvisioner};
use crate::hetzner::volume::{HetznerVolume, HetznerVolumeProvisioner};
use std::sync::Arc;

/// a hetzner cloud project by its api client; all provisioners use this client. Where a
/// resource goes is part of the resource, so the runtime has no location
pub struct HetznerProviderRuntime {
    pub api: Arc<HetznerApi>,
}

impl HetznerProviderRuntime {
    /// a runtime of an existing api client; the token is not checked
    pub fn from_api(api: Arc<HetznerApi>) -> Self {
        HetznerProviderRuntime { api }
    }

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

    /// registers the provisioners of every hetzner resource type
    pub fn register(&self, registry: &mut Registry) {
        registry.register::<HetznerSshKey, _>(self.ssh_keys());
        registry.register::<HetznerVolume, _>(self.volumes());
        registry.register::<HetznerPrimaryIp, _>(self.primary_ips());
        registry.register::<HetznerFirewall, _>(self.firewalls());
        registry.register::<HetznerServer, _>(self.servers());
        registry.register::<HetznerDnsZone, _>(self.dns_zones());
        registry.register::<HetznerDnsRecord, _>(self.dns_records());
    }
}
