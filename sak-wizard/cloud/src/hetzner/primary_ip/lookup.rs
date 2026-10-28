use crate::api::Lookup;
use crate::hetzner::primary_ip::HetznerPrimaryIpRuntime;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HetznerPrimaryIpLookup {
    pub name: String,
}

impl HetznerPrimaryIpLookup {
    pub fn new(name: &str) -> Self {
        HetznerPrimaryIpLookup {
            name: name.to_string(),
        }
    }
}

impl Lookup for HetznerPrimaryIpLookup {
    type Runtime = HetznerPrimaryIpRuntime;

    fn name(&self) -> &str {
        &self.name
    }

    fn log_text(&self) -> String {
        format!("primary ip '{}'", self.name)
    }
}
