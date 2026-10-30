use crate::api::{Labels, Resource};
use crate::hetzner::kind::declared_labels;
use crate::hetzner::ssh_key::{HetznerSshKeyLookup, HetznerSshKeyRuntime};

/// a public ssh key at hetzner; a server created with it has it in the authorized keys of
/// root
#[derive(Clone, Debug)]
pub struct HetznerSshKey {
    pub name: String,
    /// the labels that, with the name, identify it (`api::identity`); every lookup checks them
    pub identity: Labels,
    /// the labels that tell about it and are never compared (`api::Labeled::info`), written with
    /// the labels when it is created or changed for another reason
    pub info: Labels,
    /// `ssh-ed25519 AAAA... comment`
    pub public_key: String,
    pub labels: Labels,
}

impl HetznerSshKey {
    pub fn new(name: &str, public_key: &str) -> Self {
        HetznerSshKey {
            name: name.to_string(),
            identity: Labels::new(),
            info: Labels::new(),
            public_key: public_key.to_string(),
            labels: Labels::new(),
        }
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

declared_labels!(HetznerSshKey);
