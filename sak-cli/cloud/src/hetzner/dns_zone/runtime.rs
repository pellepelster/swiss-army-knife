use crate::api::{Labeled, Labels, Runtime};
use crate::hetzner::api::zones::Zone;

#[derive(Clone, Debug)]
pub struct HetznerDnsZoneRuntime {
    pub id: u64,
    pub name: String,
    pub ttl: u64,
    /// `ok`, `updating`, `error`
    pub status: String,
    /// the nameservers to set at the registrar
    pub nameservers: Vec<String>,
    pub delete_protected: bool,
    pub labels: Labels,
}

impl From<Zone> for HetznerDnsZoneRuntime {
    fn from(zone: Zone) -> Self {
        HetznerDnsZoneRuntime {
            id: zone.id,
            name: zone.name,
            ttl: zone.ttl,
            status: zone.status,
            nameservers: zone
                .authoritative_nameservers
                .map(|nameservers| nameservers.assigned)
                .unwrap_or_default(),
            delete_protected: zone.protection.delete,
            labels: zone.labels,
        }
    }
}

impl Runtime for HetznerDnsZoneRuntime {}

impl crate::hetzner::lookup::NamedRuntime for HetznerDnsZoneRuntime {
    const KIND: &'static str = "dns zone";
}

impl Labeled for HetznerDnsZoneRuntime {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
