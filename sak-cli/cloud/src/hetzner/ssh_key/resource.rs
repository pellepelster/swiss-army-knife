use crate::api::{Labeled, Labels, Resource};
use crate::hetzner::ssh_key::{HetznerSshKeyLookup, HetznerSshKeyRuntime};

/// a public ssh key stored at hetzner, servers are created with it in root's
/// authorized keys
#[derive(Clone, Debug)]
pub struct HetznerSshKey {
    pub name: String,
    /// `ssh-ed25519 AAAA... comment`
    pub public_key: String,
    pub labels: Labels,
}

impl HetznerSshKey {
    pub fn new(name: &str, public_key: &str) -> Self {
        HetznerSshKey {
            name: name.to_string(),
            public_key: public_key.to_string(),
            labels: Labels::new(),
        }
    }

    pub fn with_labels(mut self, labels: Labels) -> Self {
        self.labels = labels;
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
        HetznerSshKeyLookup::new(&self.name)
    }
}

impl Labeled for HetznerSshKey {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
