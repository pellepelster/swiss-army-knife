use crate::api::{Labels, Resource};
use crate::hetzner::api::firewalls::{FirewallRule, RuleDirection, RuleProtocol};
use crate::hetzner::firewall::{HetznerFirewallLookup, HetznerFirewallRuntime};
use crate::hetzner::kind::declared_labels;

/// a firewall at hetzner: its rules and the labels that select its servers
#[derive(Clone, Debug)]
pub struct HetznerFirewall {
    pub name: String,
    /// the labels that, with the name, identify it (`api::identity`); every lookup checks them
    pub identity: Labels,
    /// the labels that tell about it and are never compared (`api::Labeled::info`), written with
    /// the labels when it is created or changed for another reason
    pub info: Labels,
    pub rules: Vec<FirewallRule>,
    /// the firewall applies to each server with all of these labels; empty for no server
    pub applied_to_labels: Labels,
    pub labels: Labels,
}

impl HetznerFirewall {
    pub fn new(name: &str, rules: Vec<FirewallRule>) -> Self {
        HetznerFirewall {
            name: name.to_string(),
            identity: Labels::new(),
            info: Labels::new(),
            rules,
            applied_to_labels: Labels::new(),
            labels: Labels::new(),
        }
    }

    pub fn applied_to(mut self, labels: Labels) -> Self {
        self.applied_to_labels = labels;
        self
    }
}

/// an inbound rule from anywhere, `inbound_tcp("22")` for ssh
pub fn inbound_tcp(port: &str) -> FirewallRule {
    inbound(RuleProtocol::Tcp, Some(port))
}

pub fn inbound_udp(port: &str) -> FirewallRule {
    inbound(RuleProtocol::Udp, Some(port))
}

pub fn inbound_icmp() -> FirewallRule {
    inbound(RuleProtocol::Icmp, None)
}

fn inbound(protocol: RuleProtocol, port: Option<&str>) -> FirewallRule {
    FirewallRule {
        direction: RuleDirection::In,
        protocol,
        port: port.map(str::to_string),
        source_ips: vec!["0.0.0.0/0".to_string(), "::/0".to_string()],
        destination_ips: vec![],
        description: None,
    }
}

impl Resource for HetznerFirewall {
    type Runtime = HetznerFirewallRuntime;
    type Lookup = HetznerFirewallLookup;

    fn name(&self) -> &str {
        &self.name
    }

    fn as_lookup(&self) -> HetznerFirewallLookup {
        HetznerFirewallLookup::new(&self.name).with_identity(self.identity.clone())
    }
}

declared_labels!(HetznerFirewall);
