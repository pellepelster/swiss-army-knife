use crate::api::{Labeled, Labels, Resource};
use crate::hetzner::api::firewalls::{FirewallRule, RuleDirection, RuleProtocol};
use crate::hetzner::firewall::{HetznerFirewallLookup, HetznerFirewallRuntime};

/// a firewall at hetzner: its rules and the labels that select its servers
#[derive(Clone, Debug)]
pub struct HetznerFirewall {
    pub name: String,
    /// the labels that, with the name, identify it (`api::identity`); every lookup checks them
    pub identity: Labels,
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
            rules,
            applied_to_labels: Labels::new(),
            labels: Labels::new(),
        }
    }

    pub fn with_labels(mut self, labels: Labels) -> Self {
        self.labels = labels;
        self
    }

    /// sets the identity labels and adds them to the labels
    pub fn with_identity(mut self, identity: Labels) -> Self {
        self.labels.extend(identity.clone());
        self.identity = identity;
        self
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

impl Labeled for HetznerFirewall {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
