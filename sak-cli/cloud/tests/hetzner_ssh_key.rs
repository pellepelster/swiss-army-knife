//! the ssh key provisioner against the real api, the flow of HetznerSSHKeyProvisionerTest
//! of blcks: missing, created, up to date, a changed key, the same key under another
//! name, a new and a changed label, destroyed

mod support;

use sak_cloud::api::destroy_resource;
use sak_cloud::api::{
    DiffStatus, LookupContextExt, LookupProvider, LookupProviders, Provisioner, Resource,
};
use sak_cloud::hetzner::ssh_key::{HetznerSshKey, HetznerSshKeyProvisioner};
use support::HetznerTest;

const PUBLIC_KEY: &str =
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE+u0dEVRZDzzp4E1teCqF49r8ig3YEk8eaPqNWfDcPb pelle@fry";
const OTHER_PUBLIC_KEY: &str =
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDBQBtbY8Kdi4XtH2sTVSkepcdhZHWEnKAQ8PrPcIp/p pelle@fry";

#[tokio::test]
async fn ssh_key_flow() {
    let Some(hetzner) = HetznerTest::start() else {
        return;
    };

    hetzner
        .run(|hetzner| async move {
            let name = hetzner.test_id.clone();
            let provisioner = HetznerSshKeyProvisioner::new(hetzner.api.clone());
            let context = LookupProviders::new();

            let resource =
                HetznerSshKey::new(&name, PUBLIC_KEY).with_labels(hetzner.labels.clone());

            assert!(
                context.lookup(&resource.as_lookup()).await.is_err(),
                "no provider registered"
            );
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

            let runtime = provisioner.apply(&resource, &context).await.unwrap();
            assert_eq!(runtime.name, name);
            let runtime = provisioner
                .lookup(&resource.as_lookup(), &context)
                .await
                .unwrap()
                .unwrap();
            assert_eq!(
                runtime.fingerprint,
                "99:fc:9b:f4:04:69:2c:9d:30:d5:2c:d9:1e:ca:b2:76"
            );
            let diff = provisioner.diff(&resource, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::UpToDate);
            assert!(diff.changes.is_empty());

            let other_key =
                HetznerSshKey::new(&name, OTHER_PUBLIC_KEY).with_labels(hetzner.labels.clone());
            let diff = provisioner.diff(&other_key, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::HasChanges);
            assert_eq!(diff.changes.len(), 1);
            assert_eq!(diff.changes[0].name, "fingerprint");
            assert!(diff.changes[0].triggers_recreate);
            assert_eq!(
                diff.changes[0].expected.as_deref(),
                Some("d3:16:bf:af:b2:60:3e:e1:56:0d:54:3d:73:bb:9a:38")
            );
            assert_eq!(
                diff.changes[0].actual.as_deref(),
                Some("99:fc:9b:f4:04:69:2c:9d:30:d5:2c:d9:1e:ca:b2:76")
            );

            let new_name = HetznerSshKey::new(&format!("{name}-2"), PUBLIC_KEY)
                .with_labels(hetzner.labels.clone());
            let diff = provisioner.diff(&new_name, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::Duplicate);
            assert!(diff.changes.is_empty());

            let new_label = HetznerSshKey::new(&name, PUBLIC_KEY)
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
            let diff = provisioner.diff(&new_label, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::UpToDate);

            let updated_label = HetznerSshKey::new(&name, PUBLIC_KEY)
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
            let diff = provisioner.diff(&updated_label, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::UpToDate);

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
            assert!(
                !destroy_resource(&provisioner, &resource, &context)
                    .await
                    .unwrap()
            );
        })
        .await;
}
