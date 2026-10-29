//! the volume provisioner against the real api, the flow of HetznerVolumeProvisionerTest
//! of blcks: missing, created protected, a new and a changed label, the protection
//! lifted, destroyed; and a protected volume destroyed

mod support;

use sak_cloud::api::destroy_resource;
use sak_cloud::api::{DiffStatus, LookupProvider, LookupProviders, Provisioner, Resource};
use sak_cloud::hetzner::api::Location;
use sak_cloud::hetzner::volume::{HetznerVolume, HetznerVolumeProvisioner};
use support::HetznerTest;

#[tokio::test]
async fn volume_flow() {
    let Some(hetzner) = HetznerTest::start() else {
        return;
    };

    hetzner
        .run(|hetzner| async move {
            let name = hetzner.test_id.clone();
            let provisioner = HetznerVolumeProvisioner::new(hetzner.api.clone());
            let context = LookupProviders::new();

            let resource =
                HetznerVolume::new(&name, Location::nbg1, 16).with_labels(hetzner.labels.clone());

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
            assert!(runtime.delete_protected);
            assert_eq!(runtime.size_gb, 16);
            assert_eq!(runtime.location, Location::nbg1);
            assert!(runtime.device.starts_with("/dev/disk/by-id/"));
            let diff = provisioner.diff(&resource, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::UpToDate);
            assert!(diff.changes.is_empty());

            let new_label = HetznerVolume::new(&name, Location::nbg1, 16)
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

            let unprotected = HetznerVolume::new(&name, Location::nbg1, 16)
                .with_labels(hetzner.labels_with(&[("foo", "bar")]))
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

            let updated_label = HetznerVolume::new(&name, Location::nbg1, 16)
                .with_labels(hetzner.labels_with(&[("foo", "bar2")]))
                .with_protected(false);
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

#[tokio::test]
async fn destroys_a_protected_volume() {
    let Some(hetzner) = HetznerTest::start() else {
        return;
    };

    hetzner
        .run(|hetzner| async move {
            let name = hetzner.test_id.clone();
            let provisioner = HetznerVolumeProvisioner::new(hetzner.api.clone());
            let context = LookupProviders::new();
            let resource =
                HetznerVolume::new(&name, Location::nbg1, 16).with_labels(hetzner.labels.clone());

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
