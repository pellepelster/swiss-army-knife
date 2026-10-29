use crate::api::{Labeled, Labels, Resource};
use crate::hetzner::ssh_key::{HetznerSshKeyLookup, HetznerSshKeyRuntime};

/// a public ssh key stored at hetzner, servers are created with it in root's
/// authorized keys
#[derive(Clone, Debug)]
pub struct HetznerSshKey {
    pub name: String,
    /// the labels that, with the name, identify it: written with the labels, checked on every
    /// lookup (`api::identity`); a resource of the name without them is left alone
    pub identity: Labels,
    /// `ssh-ed25519 AAAA... comment`
    pub public_key: String,
    pub labels: Labels,
}

impl HetznerSshKey {
    pub fn new(name: &str, public_key: &str) -> Self {
        HetznerSshKey {
            name: name.to_string(),
            identity: Labels::new(),
            public_key: public_key.to_string(),
            labels: Labels::new(),
        }
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
}

impl Resource for HetznerSshKey {
    type Runtime = HetznerSshKeyRuntime;
    type Lookup = HetznerSshKeyLookup;

    fn name(&self) -> &str {
        &self.name
    }

    fn as_lookup(&self) -> HetznerSshKeyLookup {
        HetznerSshKeyLookup::new(&self.name).with_identity(self.identity.clone())
    }
}

impl Labeled for HetznerSshKey {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
