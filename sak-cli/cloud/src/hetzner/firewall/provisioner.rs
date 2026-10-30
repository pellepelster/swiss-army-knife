use crate::api::{DiffItem, Labels, Resource, Result};
use crate::hetzner::api::firewalls::{
    FirewallCreate, FirewallResource, FirewallRule, FirewallUpdate,
};
use crate::hetzner::api::{ErrorCode, HetznerApi, ListQuery};
use crate::hetzner::firewall::{HetznerFirewall, HetznerFirewallRuntime};
use crate::hetzner::kind::HetznerKind;
use async_trait::async_trait;
use std::collections::HashSet;
use std::sync::Arc;
use tracing::info;

pub struct HetznerFirewallProvisioner {
    api: Arc<HetznerApi>,
}

impl HetznerFirewallProvisioner {
    pub fn new(api: Arc<HetznerApi>) -> Self {
        HetznerFirewallProvisioner { api }
    }
}

fn same_rules(a: &[FirewallRule], b: &[FirewallRule]) -> bool {
    a.iter().collect::<HashSet<_>>() == b.iter().collect::<HashSet<_>>()
}

fn rules_text(rules: &[FirewallRule]) -> String {
    rules
        .iter()
        .map(FirewallRule::log_text)
        .collect::<Vec<_>>()
        .join("; ")
}

fn selector_text(labels: &Labels) -> String {
    labels
        .iter()
        .map(|(key, value)| format!("{key}={value}"))
        .collect::<Vec<_>>()
        .join(", ")
}

#[async_trait]
impl HetznerKind for HetznerFirewallProvisioner {
    type Resource = HetznerFirewall;
    type Runtime = HetznerFirewallRuntime;

    async fn fetch(&self, name: &str) -> Result<Option<HetznerFirewallRuntime>> {
        match self.api.firewalls().get_by_name(name).await? {
            Some(firewall) => Ok(Some(HetznerFirewallRuntime::from_firewall(firewall)?)),
            None => Ok(None),
        }
    }

    async fn names(&self) -> Result<Vec<String>> {
        let firewalls = self.api.firewalls().list(&ListQuery::new()).await?;
        Ok(firewalls
            .into_iter()
            .map(|firewall| firewall.name)
            .collect())
    }

    fn compare(
        &self,
        resource: &HetznerFirewall,
        runtime: &HetznerFirewallRuntime,
    ) -> Result<Vec<DiffItem>> {
        let mut changes = Vec::new();
        if !same_rules(&resource.rules, &runtime.rules) {
            changes.push(DiffItem::changed(
                "rules",
                rules_text(&resource.rules),
                rules_text(&runtime.rules),
            ));
        }
        if resource.applied_to_labels != runtime.applied_to_labels {
            changes.push(DiffItem::changed(
                "label selectors",
                selector_text(&resource.applied_to_labels),
                selector_text(&runtime.applied_to_labels),
            ));
        }
        Ok(changes)
    }

    async fn create(&self, resource: &HetznerFirewall) -> Result<HetznerFirewallRuntime> {
        info!("{} not found, creating", resource.log_text());
        let firewalls = self.api.firewalls();
        let request = FirewallCreate {
            name: resource.name.clone(),
            rules: resource.rules.clone(),
            apply_to: vec![],
            labels: Some(resource.labels.clone()),
        };
        let created = firewalls.create(&request).await?;
        firewalls.wait_for_actions(&created.actions).await?;
        HetznerFirewallRuntime::from_firewall(created.firewall)
    }

    /// the rules, and the label selectors, which a create does not set
    async fn update(
        &self,
        resource: &HetznerFirewall,
        runtime: &HetznerFirewallRuntime,
    ) -> Result<()> {
        let firewalls = self.api.firewalls();
        if !same_rules(&resource.rules, &runtime.rules) {
            let actions = firewalls.set_rules(runtime.id, &resource.rules).await?;
            firewalls.wait_for_actions(&actions).await?;
        }
        if runtime.applied_to_labels != resource.applied_to_labels {
            if !runtime.applied_to_labels.is_empty() {
                let selectors: Vec<FirewallResource> = runtime
                    .applied_to
                    .iter()
                    .filter(|applied| applied.label_selector.is_some())
                    .cloned()
                    .collect();
                let actions = firewalls
                    .remove_from_resources(runtime.id, &selectors)
                    .await?;
                firewalls.wait_for_actions(&actions).await?;
            }
            if !resource.applied_to_labels.is_empty() {
                let selector = FirewallResource::label_selector(&resource.applied_to_labels);
                match firewalls.apply_to_resources(runtime.id, &[selector]).await {
                    Ok(actions) => firewalls.wait_for_actions(&actions).await?,
                    Err(error) if error.code() == Some(ErrorCode::FirewallAlreadyApplied) => {}
                    Err(error) => return Err(error.into()),
                }
            }
        }
        Ok(())
    }

    async fn update_labels(
        &self,
        resource: &HetznerFirewall,
        runtime: &HetznerFirewallRuntime,
    ) -> Result<()> {
        let request = FirewallUpdate {
            name: Some(resource.name.clone()),
            labels: Some(resource.labels.clone()),
        };
        self.api.firewalls().update(runtime.id, &request).await?;
        Ok(())
    }

    /// removes the firewall from all resources, because hetzner does not delete a firewall
    /// that is applied
    async fn prepare_destroy(&self, runtime: &HetznerFirewallRuntime) -> Result<()> {
        if !runtime.applied_to.is_empty() {
            let firewalls = self.api.firewalls();
            let actions = firewalls
                .remove_from_resources(runtime.id, &runtime.applied_to)
                .await?;
            firewalls.wait_for_actions(&actions).await?;
        }
        Ok(())
    }

    async fn delete(&self, runtime: &HetznerFirewallRuntime) -> Result<()> {
        self.api.firewalls().delete(runtime.id).await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::{DiffStatus, LookupProviders, Provisioner};
    use crate::hetzner::firewall::resource::{inbound_icmp, inbound_tcp};
    use crate::hetzner::test_support::{labels, mock_api};
    use serde_json::{Value, json};

    fn firewall(name: &str, rules: Vec<Value>, selector: Option<&str>, labels: Value) -> Value {
        let applied_to: Vec<Value> = selector
            .map(|selector| {
                vec![
                    json!({ "type": "label_selector", "label_selector": { "selector": selector } }),
                ]
            })
            .unwrap_or_default();
        json!({ "id": 5, "name": name, "rules": rules, "applied_to": applied_to, "labels": labels })
    }

    fn http() -> Value {
        json!({ "direction": "in", "protocol": "tcp", "port": "80", "source_ips": ["0.0.0.0/0", "::/0"], "destination_ips": [] })
    }

    async fn provisioner_with(
        firewalls: Vec<Value>,
    ) -> (wiremock::MockServer, HetznerFirewallProvisioner) {
        let (server, api) = mock_api("firewalls", firewalls).await;
        (server, HetznerFirewallProvisioner::new(api))
    }

    #[tokio::test]
    async fn diff_of_a_missing_firewall() {
        let (_server, provisioner) = provisioner_with(vec![]).await;

        let diff = provisioner
            .diff(
                &HetznerFirewall::new("web", vec![inbound_tcp("80")]),
                &LookupProviders::new(),
            )
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::Missing);
    }

    #[tokio::test]
    async fn rules_compare_as_sets_whatever_their_order() {
        let (_server, provisioner) = provisioner_with(vec![firewall(
            "web",
            vec![http(), json!({ "direction": "in", "protocol": "icmp", "source_ips": ["0.0.0.0/0", "::/0"] })],
            Some("app=sak"),
            json!({}),
        )])
        .await;
        let resource = HetznerFirewall::new("web", vec![inbound_icmp(), inbound_tcp("80")])
            .applied_to(labels(&[("app", "sak")]));

        let diff = provisioner
            .diff(&resource, &LookupProviders::new())
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::UpToDate);
    }

    #[tokio::test]
    async fn changed_rules_and_selectors_are_reported_with_their_text() {
        let (_server, provisioner) = provisioner_with(vec![firewall(
            "web",
            vec![http()],
            Some("app=old"),
            json!({}),
        )])
        .await;
        let resource =
            HetznerFirewall::new("web", vec![inbound_icmp()]).applied_to(labels(&[("app", "sak")]));

        let diff = provisioner
            .diff(&resource, &LookupProviders::new())
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::HasChanges);
        assert!(!diff.needs_recreate());
        assert_eq!(
            diff.changes,
            vec![
                DiffItem::changed(
                    "rules",
                    "'0.0.0.0/0', '::/0' -> ICMP",
                    "'0.0.0.0/0', '::/0' -> TCP/80"
                ),
                DiffItem::changed("label selectors", "app=sak", "app=old"),
            ]
        );
    }

    #[test]
    fn the_runtime_merges_the_selectors() {
        let firewall: crate::hetzner::api::firewalls::Firewall =
            serde_json::from_value(firewall("web", vec![], Some("a=1,b=2"), json!({}))).unwrap();

        let runtime = HetznerFirewallRuntime::from_firewall(firewall).unwrap();

        assert_eq!(runtime.applied_to_labels, labels(&[("a", "1"), ("b", "2")]));
    }
}
