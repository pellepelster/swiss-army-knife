use crate::api::Lookup;
use crate::hetzner::firewall::HetznerFirewallRuntime;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HetznerFirewallLookup {
    pub name: String,
}

impl HetznerFirewallLookup {
    pub fn new(name: &str) -> Self {
        HetznerFirewallLookup {
            name: name.to_string(),
        }
    }
}

impl Lookup for HetznerFirewallLookup {
    type Runtime = HetznerFirewallRuntime;

    fn name(&self) -> &str {
        &self.name
    }

    fn log_text(&self) -> String {
        format!("firewall '{}'", self.name)
    }
}
