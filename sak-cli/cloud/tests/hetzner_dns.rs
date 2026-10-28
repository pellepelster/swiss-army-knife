//! the dns provisioners against the real api: a zone of the test's own, an A record in
//! it with literal values, with another value, another ttl and label, one with the
//! address of a primary ip, destroyed. The flow of HetznerDnsProvisionerTest of blcks,
//! which used a zone that existed already and mocked the servers

mod support;

use sak_cloud::api::{
    DiffStatus, Listable, Lookup, LookupProvider, LookupProviders, Provisioner, Resource,
};
use sak_cloud::hetzner::api::Location;
use sak_cloud::hetzner::dns_record::{HetznerDnsRecord, HetznerDnsRecordProvisioner, RecordValue};
use sak_cloud::hetzner::dns_zone::{
    HetznerDnsZone, HetznerDnsZoneLookup, HetznerDnsZoneProvisioner,
};
use sak_cloud::hetzner::primary_ip::{
    HetznerPrimaryIp, HetznerPrimaryIpLookup, HetznerPrimaryIpProvisioner,
};
use std::sync::Arc;
use support::HetznerTest;

#[tokio::test]
async fn dns_flow() {
    let Some(hetzner) = HetznerTest::start() else {
        return;
    };

    hetzner
        .run(|hetzner| async move {
            let zone_name = format!("sak-test-{}.de", &hetzner.test_id[..8]);
            let zones = Arc::new(HetznerDnsZoneProvisioner::new(hetzner.api.clone()));
            let ips = Arc::new(HetznerPrimaryIpProvisioner::new(hetzner.api.clone()));
            let records = HetznerDnsRecordProvisioner::new(hetzner.api.clone());
            let context = LookupProviders::new()
                .with::<HetznerDnsZoneLookup, _>(zones.clone())
                .with::<HetznerPrimaryIpLookup, _>(ips.clone());

            let zone = HetznerDnsZone::new(&zone_name)
                .with_ttl(300)
                .with_labels(hetzner.labels.clone());
            assert!(
                zones
                    .lookup(&zone.as_lookup(), &context)
                    .await
                    .unwrap()
                    .is_none()
            );
            assert_eq!(
                zones.diff(&zone, &context).await.unwrap().status,
                DiffStatus::Missing
            );
            let runtime = zones.apply(&zone, &context).await.unwrap();
            assert_eq!(runtime.name, zone_name);
            assert_eq!(runtime.ttl, 300);
            assert!(!runtime.nameservers.is_empty());
            assert!(zones.list().await.unwrap().contains(&zone.as_lookup()));
            assert_eq!(
                zones.diff(&zone, &context).await.unwrap().status,
                DiffStatus::UpToDate
            );

            let other_ttl = HetznerDnsZone::new(&zone_name)
                .with_ttl(600)
                .with_labels(hetzner.labels.clone());
            let diff = zones.diff(&other_ttl, &context).await.unwrap();
            assert_eq!(diff.changes.len(), 1);
            assert_eq!(diff.changes[0].name, "ttl");
            assert_eq!(zones.apply(&other_ttl, &context).await.unwrap().ttl, 600);

            let record = HetznerDnsRecord::a(
                &hetzner.test_id,
                zone.as_lookup(),
                vec![RecordValue::literal("127.0.0.1")],
            )
            .with_labels(hetzner.labels.clone());
            assert!(
                records
                    .lookup(&record.as_lookup(), &context)
                    .await
                    .unwrap()
                    .is_none()
            );
            assert_eq!(
                records.diff(&record, &context).await.unwrap().status,
                DiffStatus::Missing
            );
            records.apply(&record, &context).await.unwrap();
            let runtime = records
                .lookup(&record.as_lookup(), &context)
                .await
                .unwrap()
                .unwrap();
            assert_eq!(runtime.name, hetzner.test_id);
            assert_eq!(runtime.values, vec!["127.0.0.1"]);
            assert_eq!(
                records.diff(&record, &context).await.unwrap().status,
                DiffStatus::UpToDate
            );

            let other_value = HetznerDnsRecord::a(
                &hetzner.test_id,
                zone.as_lookup(),
                vec![RecordValue::literal("127.0.0.2")],
            )
            .with_labels(hetzner.labels.clone());
            let diff = records.diff(&other_value, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::HasChanges);
            assert_eq!(diff.changes.len(), 1);
            assert_eq!(diff.changes[0].name, "value");
            assert_eq!(diff.changes[0].expected.as_deref(), Some("127.0.0.2"));
            assert_eq!(diff.changes[0].actual.as_deref(), Some("127.0.0.1"));
            records.apply(&other_value, &context).await.unwrap();
            assert_eq!(
                records.diff(&other_value, &context).await.unwrap().status,
                DiffStatus::UpToDate
            );

            let other_ttl_and_label = other_value
                .clone()
                .with_ttl(60)
                .with_labels(hetzner.labels_with(&[("foo", "bar")]));
            let diff = records.diff(&other_ttl_and_label, &context).await.unwrap();
            let names: Vec<&str> = diff
                .changes
                .iter()
                .map(|change| change.name.as_str())
                .collect();
            assert_eq!(names, vec!["ttl", "label 'foo'"]);
            let runtime = records.apply(&other_ttl_and_label, &context).await.unwrap();
            assert_eq!(runtime.ttl, Some(60));
            assert_eq!(
                records
                    .diff(&other_ttl_and_label, &context)
                    .await
                    .unwrap()
                    .status,
                DiffStatus::UpToDate
            );

            let ip = HetznerPrimaryIp::ipv4(&hetzner.test_id, Location::hel1)
                .with_protected(false)
                .with_labels(hetzner.labels.clone());
            let address = ips.apply(&ip, &context).await.unwrap().ip;
            let from_ip = HetznerDnsRecord::a(
                "backend",
                zone.as_lookup(),
                vec![RecordValue::PrimaryIp(ip.as_lookup())],
            )
            .with_labels(hetzner.labels.clone());
            let diff = records.diff(&from_ip, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::Missing);
            let runtime = records.apply(&from_ip, &context).await.unwrap();
            assert_eq!(runtime.values, vec![address]);
            assert_eq!(
                records.diff(&from_ip, &context).await.unwrap().status,
                DiffStatus::UpToDate
            );

            let mut pending = LookupProviders::new()
                .with::<HetznerDnsZoneLookup, _>(zones.clone())
                .with::<HetznerPrimaryIpLookup, _>(ips.clone());
            pending.mark_pending(ip.as_lookup().resource_ref());
            let diff = records.diff(&from_ip, &pending).await.unwrap();
            assert_eq!(
                diff.changes[0].expected.as_deref(),
                Some("<known after apply>")
            );

            assert!(
                records
                    .destroy(&record.as_lookup(), &context)
                    .await
                    .unwrap()
            );
            assert!(
                records
                    .lookup(&record.as_lookup(), &context)
                    .await
                    .unwrap()
                    .is_none()
            );
            assert!(
                !records
                    .destroy(&record.as_lookup(), &context)
                    .await
                    .unwrap()
            );
            assert!(zones.destroy(&zone.as_lookup(), &context).await.unwrap());
            assert!(
                zones
                    .lookup(&zone.as_lookup(), &context)
                    .await
                    .unwrap()
                    .is_none()
            );
            assert_eq!(
                records.diff(&from_ip, &context).await.unwrap().status,
                DiffStatus::ParentMissing
            );
        })
        .await;
}
