use crate::api::Lookup;
use crate::hetzner::dns_zone::HetznerDnsZoneRuntime;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HetznerDnsZoneLookup {
    pub name: String,
}

impl HetznerDnsZoneLookup {
    pub fn new(name: &str) -> Self {
        HetznerDnsZoneLookup {
            name: name.to_string(),
        }
    }
}

impl Lookup for HetznerDnsZoneLookup {
    type Runtime = HetznerDnsZoneRuntime;

    fn name(&self) -> &str {
        &self.name
    }

    fn log_text(&self) -> String {
        format!("dns zone '{}'", self.name)
    }
}
