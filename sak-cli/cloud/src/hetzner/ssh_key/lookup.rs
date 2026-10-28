use crate::api::Lookup;
use crate::hetzner::ssh_key::HetznerSshKeyRuntime;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HetznerSshKeyLookup {
    pub name: String,
}

impl HetznerSshKeyLookup {
    pub fn new(name: &str) -> Self {
        HetznerSshKeyLookup {
            name: name.to_string(),
        }
    }
}

impl Lookup for HetznerSshKeyLookup {
    type Runtime = HetznerSshKeyRuntime;

    fn name(&self) -> &str {
        &self.name
    }

    fn log_text(&self) -> String {
        format!("ssh key '{}'", self.name)
    }
}
