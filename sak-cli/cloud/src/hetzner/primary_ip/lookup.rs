use crate::api::{Labels, Lookup};
use crate::hetzner::primary_ip::HetznerPrimaryIpRuntime;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HetznerPrimaryIpLookup {
    pub name: String,
    /// the labels that, with the name, identify the resource (`api::identity`)
    pub identity: Labels,
}

impl HetznerPrimaryIpLookup {
    pub fn new(name: &str) -> Self {
        HetznerPrimaryIpLookup {
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

impl Lookup for HetznerPrimaryIpLookup {
    type Runtime = HetznerPrimaryIpRuntime;

    fn name(&self) -> &str {
        &self.name
    }

    fn identity(&self) -> &Labels {
        &self.identity
    }

    fn log_text(&self) -> String {
        format!("primary ip '{}'", self.name)
    }
}
