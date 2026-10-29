use crate::api::{Labels, Lookup};
use crate::hetzner::server::HetznerServerRuntime;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HetznerServerLookup {
    pub name: String,
    /// the labels that, with the name, identify the resource (`api::identity`)
    pub identity: Labels,
}

impl HetznerServerLookup {
    pub fn new(name: &str) -> Self {
        HetznerServerLookup {
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

impl Lookup for HetznerServerLookup {
    type Runtime = HetznerServerRuntime;

    fn name(&self) -> &str {
        &self.name
    }

    fn identity(&self) -> &Labels {
        &self.identity
    }

    fn log_text(&self) -> String {
        format!("server '{}'", self.name)
    }
}
