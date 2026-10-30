use crate::api::{Labeled, Labels, Resource};
use crate::hetzner::api::Location;
use crate::hetzner::api::primary_ips::PrimaryIpType;
use crate::hetzner::primary_ip::{HetznerPrimaryIpLookup, HetznerPrimaryIpRuntime};

/// a public address in a location, created without a server. A server resource refers to it
/// by its lookup and is created with it, also when it is recreated. It is not deleted with
/// its server (`auto_delete` stays off)
#[derive(Clone, Debug)]
pub struct HetznerPrimaryIp {
    pub name: String,
    /// the labels that, with the name, identify it (`api::identity`); every lookup checks them
    pub identity: Labels,
    /// the labels that tell about it and are never compared (`api::Labeled::info`), written with
    /// the labels when it is created or changed for another reason
    pub info: Labels,
    pub ip_type: PrimaryIpType,
    pub location: Location,
    /// the delete protection at hetzner, on by default; destroy removes it
    pub protected: bool,
    pub labels: Labels,
}

impl HetznerPrimaryIp {
    pub fn new(name: &str, ip_type: PrimaryIpType, location: Location) -> Self {
        HetznerPrimaryIp {
            name: name.to_string(),
            identity: Labels::new(),
            info: Labels::new(),
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

    /// sets the identity labels and adds them to the labels
    pub fn with_identity(mut self, identity: Labels) -> Self {
        self.labels.extend(identity.clone());
        self.identity = identity;
        self
    }

    /// sets the info labels and adds them to the labels
    pub fn with_info(mut self, info: Labels) -> Self {
        self.labels.extend(info.clone());
        self.info = info;
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

    fn info(&self) -> &Labels {
        &self.info
    }
}
