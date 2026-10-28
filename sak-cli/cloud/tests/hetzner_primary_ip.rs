//! the primary ip provisioner against the real api, after the flow of the floating ip
//! test of blcks: missing, created protected and unassigned, another type, a new and a
//! changed label, the protection lifted, destroyed

mod support;

use sak_cloud::api::{DiffStatus, LookupProvider, LookupProviders, Provisioner, Resource};
use sak_cloud::hetzner::api::Location;
use sak_cloud::hetzner::api::primary_ips::PrimaryIpType;
use sak_cloud::hetzner::primary_ip::{HetznerPrimaryIp, HetznerPrimaryIpProvisioner};
use support::HetznerTest;

#[tokio::test]
async fn primary_ip_flow() {
    let Some(hetzner) = HetznerTest::start() else {
        return;
    };

    hetzner
        .run(|hetzner| async move {
            let name = hetzner.test_id.clone();
            let provisioner = HetznerPrimaryIpProvisioner::new(hetzner.api.clone());
            let context = LookupProviders::new();

            let resource =
                HetznerPrimaryIp::ipv4(&name, Location::nbg1).with_labels(hetzner.labels.clone());

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
            assert_eq!(runtime.ip_type, PrimaryIpType::Ipv4);
            assert!(runtime.delete_protected);
            assert!(!runtime.auto_delete);
            assert!(runtime.assignee_id.is_none());
            assert_eq!(runtime.location, Location::nbg1);
            assert!(!runtime.ip.is_empty());
            let diff = provisioner.diff(&resource, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::UpToDate);
            assert!(diff.changes.is_empty());

            let other_type =
                HetznerPrimaryIp::ipv6(&name, Location::nbg1).with_labels(hetzner.labels.clone());
            let diff = provisioner.diff(&other_type, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::HasChanges);
            assert_eq!(diff.changes.len(), 1);
            assert_eq!(diff.changes[0].name, "type");
            assert!(diff.changes[0].triggers_recreate);
            assert_eq!(diff.changes[0].expected.as_deref(), Some("ipv6"));
            assert_eq!(diff.changes[0].actual.as_deref(), Some("ipv4"));

            let new_label = HetznerPrimaryIp::ipv4(&name, Location::nbg1)
                .with_labels(hetzner.labels_with(&[("foo", "bar")]));
            let diff = provisioner.diff(&new_label, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::HasChanges);
            assert_eq!(diff.changes.len(), 1);
            assert!(diff.changes[0].missing);
            assert_eq!(diff.changes[0].name, "label 'foo'");
            assert_eq!(
                provisioner.apply(&new_label, &context).await.unwrap().name,
                name
            );
            assert_eq!(
                provisioner.diff(&new_label, &context).await.unwrap().status,
                DiffStatus::UpToDate
            );

            let updated_label = HetznerPrimaryIp::ipv4(&name, Location::nbg1)
                .with_labels(hetzner.labels_with(&[("foo", "bar2")]));
            let diff = provisioner.diff(&updated_label, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::HasChanges);
            assert_eq!(diff.changes.len(), 1);
            assert_eq!(diff.changes[0].name, "label 'foo'");
            assert_eq!(diff.changes[0].expected.as_deref(), Some("bar2"));
            assert_eq!(diff.changes[0].actual.as_deref(), Some("bar"));
            assert_eq!(
                provisioner
                    .apply(&updated_label, &context)
                    .await
                    .unwrap()
                    .name,
                name
            );
            assert_eq!(
                provisioner
                    .diff(&updated_label, &context)
                    .await
                    .unwrap()
                    .status,
                DiffStatus::UpToDate
            );

            let unprotected = HetznerPrimaryIp::ipv4(&name, Location::nbg1)
                .with_labels(hetzner.labels_with(&[("foo", "bar2")]))
                .with_protected(false);
            let diff = provisioner.diff(&unprotected, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::HasChanges);
            assert_eq!(diff.changes.len(), 1);
            assert_eq!(diff.changes[0].name, "delete protection");
            assert_eq!(diff.changes[0].expected.as_deref(), Some("false"));
            assert_eq!(diff.changes[0].actual.as_deref(), Some("true"));
            assert_eq!(
                provisioner
                    .apply(&unprotected, &context)
                    .await
                    .unwrap()
                    .name,
                name
            );
            assert_eq!(
                provisioner
                    .diff(&unprotected, &context)
                    .await
                    .unwrap()
                    .status,
                DiffStatus::UpToDate
            );

            assert!(
                provisioner
                    .destroy(&resource.as_lookup(), &context)
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

#[tokio::test]
async fn destroys_a_protected_primary_ip() {
    let Some(hetzner) = HetznerTest::start() else {
        return;
    };

    hetzner
        .run(|hetzner| async move {
            let name = hetzner.test_id.clone();
            let provisioner = HetznerPrimaryIpProvisioner::new(hetzner.api.clone());
            let context = LookupProviders::new();
            let resource =
                HetznerPrimaryIp::ipv4(&name, Location::nbg1).with_labels(hetzner.labels.clone());

            provisioner.apply(&resource, &context).await.unwrap();
            assert!(
                provisioner
                    .lookup(&resource.as_lookup(), &context)
                    .await
                    .unwrap()
                    .unwrap()
                    .delete_protected
            );

            assert!(
                provisioner
                    .destroy(&resource.as_lookup(), &context)
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
