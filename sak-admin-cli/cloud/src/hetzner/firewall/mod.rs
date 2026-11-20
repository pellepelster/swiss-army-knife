//! a firewall with its rules, applied to the servers that match a label selector: the
//! resource, its lookup and runtime, the provisioner

mod provisioner;
mod resource;
mod runtime;

pub use crate::hetzner::api::firewalls::{FirewallRule, RuleDirection, RuleProtocol};
pub use provisioner::HetznerFirewallProvisioner;
pub use resource::{HetznerFirewall, inbound_icmp, inbound_tcp, inbound_udp};
pub use runtime::HetznerFirewallRuntime;

/// the lookup of a firewall by its name
pub type HetznerFirewallLookup = crate::hetzner::lookup::NamedLookup<HetznerFirewallRuntime>;
