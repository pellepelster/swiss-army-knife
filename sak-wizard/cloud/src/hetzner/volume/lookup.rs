use crate::api::Lookup;
use crate::hetzner::volume::HetznerVolumeRuntime;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HetznerVolumeLookup {
    pub name: String,
}

impl HetznerVolumeLookup {
    pub fn new(name: &str) -> Self {
        HetznerVolumeLookup {
            name: name.to_string(),
        }
    }
}

impl Lookup for HetznerVolumeLookup {
    type Runtime = HetznerVolumeRuntime;

    fn name(&self) -> &str {
        &self.name
    }

    fn log_text(&self) -> String {
        format!("volume '{}'", self.name)
    }
}
