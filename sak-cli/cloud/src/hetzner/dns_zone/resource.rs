use crate::api::{Labeled, Labels, Resource};
use crate::hetzner::dns_zone::{HetznerDnsZoneLookup, HetznerDnsZoneRuntime};

/// a primary dns zone at hetzner. The registrar of the name must point to the nameservers
/// that the runtime reports
#[derive(Clone, Debug)]
pub struct HetznerDnsZone {
    /// `example.de`, lower case, a registrable name (no subdomain)
    pub name: String,
    /// the default ttl of the rrsets in seconds; `None` for the default of hetzner
    pub ttl: Option<u64>,
    pub labels: Labels,
}

impl HetznerDnsZone {
    pub fn new(name: &str) -> Self {
        HetznerDnsZone {
            name: name.to_string(),
            ttl: None,
            labels: Labels::new(),
        }
    }

    pub fn with_ttl(mut self, ttl: u64) -> Self {
        self.ttl = Some(ttl);
        self
    }

    pub fn with_labels(mut self, labels: Labels) -> Self {
        self.labels = labels;
        self
    }
}

impl Resource for HetznerDnsZone {
    type Runtime = HetznerDnsZoneRuntime;
    type Lookup = HetznerDnsZoneLookup;

    fn name(&self) -> &str {
        &self.name
    }

    fn as_lookup(&self) -> HetznerDnsZoneLookup {
        HetznerDnsZoneLookup::new(&self.name)
    }
}

impl Labeled for HetznerDnsZone {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
