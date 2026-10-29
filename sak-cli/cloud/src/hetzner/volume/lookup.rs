use crate::api::{Labels, Lookup};
use crate::hetzner::volume::HetznerVolumeRuntime;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HetznerVolumeLookup {
    pub name: String,
    /// the labels that, with the name, identify the resource (`api::identity`)
    pub identity: Labels,
}

impl HetznerVolumeLookup {
    pub fn new(name: &str) -> Self {
        HetznerVolumeLookup {
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

impl Lookup for HetznerVolumeLookup {
    type Runtime = HetznerVolumeRuntime;

    fn name(&self) -> &str {
        &self.name
    }

    fn identity(&self) -> &Labels {
        &self.identity
    }

    fn log_text(&self) -> String {
        format!("volume '{}'", self.name)
    }
}
