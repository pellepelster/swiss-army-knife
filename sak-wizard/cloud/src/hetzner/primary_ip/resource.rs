use crate::api::{Labeled, Labels, Resource};
use crate::hetzner::api::Location;
use crate::hetzner::api::primary_ips::PrimaryIpType;
use crate::hetzner::primary_ip::{HetznerPrimaryIpLookup, HetznerPrimaryIpRuntime};

/// a public address created on its own, in a location; a server resource takes it by
/// lookup and is created with it, and gets it back when recreated. It is never deleted
/// with its server (`auto_delete` stays off)
#[derive(Clone, Debug)]
pub struct HetznerPrimaryIp {
    pub name: String,
    pub ip_type: PrimaryIpType,
    pub location: Location,
    /// delete protection at hetzner, on by default; destroy lifts it
    pub protected: bool,
    pub labels: Labels,
}

impl HetznerPrimaryIp {
    pub fn new(name: &str, ip_type: PrimaryIpType, location: Location) -> Self {
        HetznerPrimaryIp {
            name: name.to_string(),
            ip_type,
            location,
            protected: true,
            labels: Labels::new(),
        }
    }

    pub fn ipv4(name: &str, location: Location) -> Self {
        Self::new(name, PrimaryIpType::Ipv4, location)
    }

    pub fn ipv6(name: &str, location: Location) -> Self {
        Self::new(name, PrimaryIpType::Ipv6, location)
    }

    pub fn with_labels(mut self, labels: Labels) -> Self {
        self.labels = labels;
        self
    }

    pub fn with_protected(mut self, protected: bool) -> Self {
        self.protected = protected;
        self
    }
}

impl Resource for HetznerPrimaryIp {
    type Runtime = HetznerPrimaryIpRuntime;
    type Lookup = HetznerPrimaryIpLookup;

    fn name(&self) -> &str {
        &self.name
    }

    fn as_lookup(&self) -> HetznerPrimaryIpLookup {
        HetznerPrimaryIpLookup::new(&self.name)
    }
}

impl Labeled for HetznerPrimaryIp {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
