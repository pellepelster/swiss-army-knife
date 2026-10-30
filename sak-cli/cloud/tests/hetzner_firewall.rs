//! the firewall provisioner against the real api, the flow of HetznerFirewallProvisionerTest
//! of blcks: missing, created with two rules, the rules replaced, a new and a changed
//! label, applied to a label selector and moved to another, destroyed

mod support;

use sak_cloud::api::destroy_resource;
use sak_cloud::api::{DiffStatus, LookupProvider, LookupProviders, Provisioner, Resource};
use sak_cloud::hetzner::firewall::{
    FirewallRule, HetznerFirewall, HetznerFirewallProvisioner, RuleDirection, RuleProtocol,
};
use support::HetznerTest;

fn rule(protocol: RuleProtocol, port: Option<&str>, description: &str) -> FirewallRule {
    FirewallRule {
        direction: RuleDirection::In,
        protocol,
        port: port.map(str::to_string),
        source_ips: vec!["0.0.0.0/0".to_string(), "::/0".to_string()],
        destination_ips: vec![],
        description: Some(description.to_string()),
    }
}

#[tokio::test]
async fn firewall_flow() {
    let Some(hetzner) = HetznerTest::start() else {
        return;
    };

    hetzner
        .run(|hetzner| async move {
            let name = hetzner.test_id.clone();
            let provisioner = HetznerFirewallProvisioner::new(hetzner.api.clone());
            let context = LookupProviders::new();
            let http = rule(RuleProtocol::Tcp, Some("80"), "allow http");
            let https = rule(RuleProtocol::Tcp, Some("443"), "allow https");
            let icmp = rule(RuleProtocol::Icmp, None, "allow icmp");

            let resource = HetznerFirewall::new(&name, vec![http.clone(), https.clone()])
                .with_labels(hetzner.labels.clone());

            assert!(
                provisioner
                    .lookup(&resource.as_lookup(), &context)
                    .await
                    .unwrap()
                    .is_none()
            );
            let diff = provisioner.diff(&resource, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::Missing);
            assert!(diff.changes.is_empty());

            assert_eq!(
                provisioner.apply(&resource, &context).await.unwrap().name,
                name
            );
            let runtime = provisioner
                .lookup(&resource.as_lookup(), &context)
                .await
                .unwrap()
                .unwrap();
            assert_eq!(runtime.name, name);
            assert_eq!(runtime.rules.len(), 2);
            let diff = provisioner.diff(&resource, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::UpToDate);
            assert!(diff.changes.is_empty());

            let new_rules =
                HetznerFirewall::new(&name, vec![icmp.clone()]).with_labels(hetzner.labels.clone());
            let diff = provisioner.diff(&new_rules, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::HasChanges);
            assert_eq!(diff.changes.len(), 1);
            assert_eq!(diff.changes[0].name, "rules");
            assert_eq!(
                diff.changes[0].expected.as_deref(),
                Some("'0.0.0.0/0', '::/0' -> ICMP")
            );
            assert_eq!(
                diff.changes[0].actual.as_deref(),
                Some("'0.0.0.0/0', '::/0' -> TCP/80; '0.0.0.0/0', '::/0' -> TCP/443")
            );
            assert_eq!(
                provisioner.apply(&new_rules, &context).await.unwrap().name,
                name
            );
            let runtime = provisioner
                .lookup(&new_rules.as_lookup(), &context)
                .await
                .unwrap()
                .unwrap();
            assert_eq!(runtime.rules.len(), 1);
            assert_eq!(runtime.rules[0].protocol, RuleProtocol::Icmp);
            assert_eq!(
                provisioner.diff(&new_rules, &context).await.unwrap().status,
                DiffStatus::UpToDate
            );

            let new_label = HetznerFirewall::new(&name, vec![icmp.clone()])
                .with_labels(hetzner.labels_with(&[("foo", "bar")]));
            let diff = provisioner.diff(&new_label, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::HasChanges);
            assert_eq!(diff.changes.len(), 1);
            assert_eq!(diff.changes[0].kind, sak_cloud::api::ChangeKind::Missing);
            assert_eq!(diff.changes[0].name, "label 'foo'");
            provisioner.apply(&new_label, &context).await.unwrap();
            assert_eq!(
                provisioner.diff(&new_label, &context).await.unwrap().status,
                DiffStatus::UpToDate
            );

            let updated_label = HetznerFirewall::new(&name, vec![icmp.clone()])
                .with_labels(hetzner.labels_with(&[("foo", "bar2")]));
            let diff = provisioner.diff(&updated_label, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::HasChanges);
            assert_eq!(diff.changes.len(), 1);
            assert_eq!(diff.changes[0].name, "label 'foo'");
            assert_eq!(diff.changes[0].expected.as_deref(), Some("bar2"));
            assert_eq!(diff.changes[0].actual.as_deref(), Some("bar"));
            provisioner.apply(&updated_label, &context).await.unwrap();
            assert_eq!(
                provisioner
                    .diff(&updated_label, &context)
                    .await
                    .unwrap()
                    .status,
                DiffStatus::UpToDate
            );

            let selector_a = updated_label
                .clone()
                .applied_to([("selector-a".to_string(), name.clone())].into());
            provisioner.apply(&selector_a, &context).await.unwrap();
            let runtime = provisioner
                .lookup(&selector_a.as_lookup(), &context)
                .await
                .unwrap()
                .unwrap();
            assert_eq!(
                runtime.applied_to_labels,
                [("selector-a".to_string(), name.clone())].into()
            );

            let selector_b = updated_label
                .clone()
                .applied_to([("selector-b".to_string(), name.clone())].into());
            let diff = provisioner.diff(&selector_b, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::HasChanges);
            assert_eq!(diff.changes.len(), 1);
            assert_eq!(diff.changes[0].name, "label selectors");
            provisioner.apply(&selector_b, &context).await.unwrap();
            let runtime = provisioner
                .lookup(&selector_b.as_lookup(), &context)
                .await
                .unwrap()
                .unwrap();
            assert_eq!(
                runtime.applied_to_labels,
                [("selector-b".to_string(), name.clone())].into()
            );
            assert_eq!(
                provisioner
                    .diff(&selector_b, &context)
                    .await
                    .unwrap()
                    .status,
                DiffStatus::UpToDate
            );

            assert!(
                destroy_resource(&provisioner, &resource, &context)
                    .await
                    .unwrap()
            );
            assert!(
                provisioner
                    .lookup(&resource.as_lookup(), &context)
                    .await
                    .unwrap()
                    .is_none()
            );
        })
        .await;
}
