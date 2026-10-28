use crate::api::{Error, Labeled, Labels, Result, Runtime};
use crate::hetzner::api::firewalls::{Firewall, FirewallResource, FirewallRule};

#[derive(Clone, Debug)]
pub struct HetznerFirewallRuntime {
    pub id: u64,
    pub name: String,
    pub rules: Vec<FirewallRule>,
    /// the labels of the selectors the firewall is applied to, all of them merged
    pub applied_to_labels: Labels,
    /// what the firewall is applied to as the api reports it, servers included
    pub applied_to: Vec<FirewallResource>,
    pub labels: Labels,
}

impl HetznerFirewallRuntime {
    pub fn from_firewall(firewall: Firewall) -> Result<Self> {
        let mut applied_to_labels = Labels::new();
        for resource in &firewall.applied_to {
            if let Some(selector) = &resource.label_selector {
                for term in selector.selector.split(',').filter(|term| !term.is_empty()) {
                    let (key, value) = term.split_once('=').ok_or_else(|| {
                        Error::message(format!("invalid label selector '{}'", selector.selector))
                    })?;
                    applied_to_labels.insert(key.to_string(), value.to_string());
                }
            }
        }
        Ok(HetznerFirewallRuntime {
            id: firewall.id,
            name: firewall.name,
            rules: firewall.rules,
            applied_to_labels,
            applied_to: firewall.applied_to,
            labels: firewall.labels,
        })
    }
}

impl Runtime for HetznerFirewallRuntime {}

impl Labeled for HetznerFirewallRuntime {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
