use crate::api::{Labeled, Labels, Resource};
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

    fn info(&self) -> &Labels {
        &self.info
    }
}
