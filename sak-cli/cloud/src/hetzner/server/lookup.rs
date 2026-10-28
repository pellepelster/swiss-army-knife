use crate::api::Lookup;
use crate::hetzner::server::HetznerServerRuntime;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HetznerServerLookup {
    pub name: String,
}

impl HetznerServerLookup {
    pub fn new(name: &str) -> Self {
        HetznerServerLookup {
            name: name.to_string(),
        }
    }
}

impl Lookup for HetznerServerLookup {
    type Runtime = HetznerServerRuntime;

    fn name(&self) -> &str {
        &self.name
    }

    fn log_text(&self) -> String {
        format!("server '{}'", self.name)
    }
}
