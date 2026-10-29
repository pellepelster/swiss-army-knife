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
    /// the labels that, with the name, identify it: written with the labels, checked on every
    /// lookup (`api::identity`); a resource of the name without them is left alone
    pub identity: Labels,
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
            identity: Labels::new(),
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

    /// the identity labels, also written as labels: a lookup of it checks them
    pub fn with_identity(mut self, identity: Labels) -> Self {
        self.labels.extend(identity.clone());
        self.identity = identity;
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
        HetznerPrimaryIpLookup::new(&self.name).with_identity(self.identity.clone())
    }
}

impl Labeled for HetznerPrimaryIp {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
