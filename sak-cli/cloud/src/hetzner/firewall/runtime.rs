use crate::api::{Labeled, Labels, Runtime};
use crate::hetzner::api::firewalls::{Firewall, FirewallResource, FirewallRule};
use std::collections::BTreeSet;

#[derive(Clone, Debug)]
pub struct HetznerFirewallRuntime {
    pub id: u64,
    pub name: String,
    pub rules: Vec<FirewallRule>,
    /// the label selectors of the firewall, each in the spelling of `selector`; compared as they
    /// are, so a selector sak-cloud does not know (`!=`, `in (…)`, a bare key) is one more
    /// selector, no error
    pub selectors: BTreeSet<String>,
    /// what the firewall is applied to, as the api reports it, also single servers
    pub applied_to: Vec<FirewallResource>,
    pub labels: Labels,
}

impl HetznerFirewallRuntime {
    pub fn from_firewall(firewall: Firewall) -> Self {
        let selectors = firewall
            .applied_to
            .iter()
            .filter_map(|resource| resource.label_selector.as_ref())
            .map(|label_selector| selector(&label_selector.selector))
            .filter(|selector| !selector.is_empty())
            .collect();
        HetznerFirewallRuntime {
            id: firewall.id,
            name: firewall.name,
            rules: firewall.rules,
            selectors,
            applied_to: firewall.applied_to,
            labels: firewall.labels,
        }
    }
}

/// a label selector in one spelling, to compare it: its terms trimmed and sorted, `key==value`
/// as `key=value`; the commas of `in (…)` and `notin (…)` stay in their term, any other term
/// stays as it is
pub(crate) fn selector(text: &str) -> String {
    let mut terms: Vec<String> = Vec::new();
    let (mut depth, mut start) = (0usize, 0);
    for (index, char) in text.char_indices() {
        match char {
            '(' => depth += 1,
            ')' => depth = depth.saturating_sub(1),
            ',' if depth == 0 => {
                terms.push(term(&text[start..index]));
                start = index + 1;
            }
            _ => {}
        }
    }
    terms.push(term(&text[start..]));
    terms.retain(|term| !term.is_empty());
    terms.sort();
    terms.join(",")
}

/// one term of a selector, an equality `key==value` as `key=value`
fn term(text: &str) -> String {
    let text = text.trim();
    let equality = text
        .split_once("==")
        .or_else(|| text.split_once('='))
        .filter(|(key, _)| !key.ends_with('!'));
    match equality {
        Some((key, value)) => format!("{}={}", key.trim(), value.trim()),
        None => text.to_string(),
    }
}

impl Runtime for HetznerFirewallRuntime {}

impl crate::hetzner::lookup::NamedRuntime for HetznerFirewallRuntime {
    const KIND: &'static str = "firewall";
}

impl Labeled for HetznerFirewallRuntime {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
