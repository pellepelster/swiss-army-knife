use crate::api::{Labels, Lookup};
use crate::hetzner::ssh_key::HetznerSshKeyRuntime;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HetznerSshKeyLookup {
    pub name: String,
    /// the labels that, with the name, identify the resource (`api::identity`)
    pub identity: Labels,
}

impl HetznerSshKeyLookup {
    pub fn new(name: &str) -> Self {
        HetznerSshKeyLookup {
            name: name.to_string(),
            identity: Labels::new(),
        }
    }

    /// the lookup that also checks these labels, see `api::identity`
    pub fn with_identity(mut self, identity: Labels) -> Self {
        self.identity = identity;
        self
    }
}

impl Lookup for HetznerSshKeyLookup {
    type Runtime = HetznerSshKeyRuntime;

    fn name(&self) -> &str {
        &self.name
    }

    fn identity(&self) -> &Labels {
        &self.identity
    }

    fn log_text(&self) -> String {
        format!("ssh key '{}'", self.name)
    }
}
