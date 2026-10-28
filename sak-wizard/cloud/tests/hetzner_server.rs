//! the server provisioner against the real api, the flow of HetznerServerProvisionerTest
//! of blcks without its private network: created with an ssh key, a volume and a
//! primary ip, the diffs of another image and another ssh key, destroyed and created
//! again with the same address

mod support;

use sak_cloud::api::{DiffStatus, LookupProvider, LookupProviders, Provisioner, Resource};
use sak_cloud::hetzner::primary_ip::{
    HetznerPrimaryIp, HetznerPrimaryIpLookup, HetznerPrimaryIpProvisioner,
};
use sak_cloud::hetzner::server::{HetznerServer, HetznerServerProvisioner, UserData};
use sak_cloud::hetzner::ssh_key::{HetznerSshKey, HetznerSshKeyLookup, HetznerSshKeyProvisioner};
use sak_cloud::hetzner::volume::{HetznerVolume, HetznerVolumeLookup, HetznerVolumeProvisioner};
use std::sync::Arc;
use support::HetznerTest;

const KEY_1: &str =
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE+u0dEVRZDzzp4E1teCqF49r8ig3YEk8eaPqNWfDcPb pelle@fry";
const KEY_2: &str =
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE+u0dEVRZDzzp4E1teCqF49r8ig3YEk8eaPqNWfDcPa pelle@fry";

#[tokio::test]
async fn server_flow() {
    let Some(hetzner) = HetznerTest::start() else {
        return;
    };

    hetzner
        .run(|hetzner| async move {
            let name = hetzner.test_id.clone();
            let location = hetzner.location();
            let server_type = hetzner.server_type();
            let api = hetzner.api.clone();
            let ssh_keys = Arc::new(HetznerSshKeyProvisioner::new(api.clone()));
            let volumes = Arc::new(HetznerVolumeProvisioner::new(api.clone()));
            let ips = Arc::new(HetznerPrimaryIpProvisioner::new(api.clone()));
            let servers = HetznerServerProvisioner::new(api.clone());
            let context = LookupProviders::new()
                .with::<HetznerSshKeyLookup, _>(ssh_keys.clone())
                .with::<HetznerVolumeLookup, _>(volumes.clone())
                .with::<HetznerPrimaryIpLookup, _>(ips.clone());

            let key_1 =
                HetznerSshKey::new(&format!("{name}-1"), KEY_1).with_labels(hetzner.labels.clone());
            let key_2 =
                HetznerSshKey::new(&format!("{name}-2"), KEY_2).with_labels(hetzner.labels.clone());
            let volume = HetznerVolume::new(&name, location, 16)
                .with_protected(false)
                .with_labels(hetzner.labels.clone());
            let ip = HetznerPrimaryIp::ipv4(&name, location)
                .with_protected(false)
                .with_labels(hetzner.labels.clone());
            ssh_keys.apply(&key_1, &context).await.unwrap();
            ssh_keys.apply(&key_2, &context).await.unwrap();
            volumes.apply(&volume, &context).await.unwrap();
            let address = ips.apply(&ip, &context).await.unwrap().ip;

            let server = HetznerServer::new(&name, location, server_type, "#cloud-config\n")
                .with_ssh_keys(vec![key_1.as_lookup()])
                .with_volumes(vec![volume.as_lookup()])
                .with_ipv4(ip.as_lookup())
                .with_labels(hetzner.labels.clone());

            assert!(
                servers
                    .lookup(&server.as_lookup(), &context)
                    .await
                    .unwrap()
                    .is_none()
            );
            assert_eq!(
                servers.diff(&server, &context).await.unwrap().status,
                DiffStatus::Missing
            );

            let runtime = servers.apply(&server, &context).await.unwrap();
            assert_eq!(runtime.name, name);
            assert_eq!(runtime.public_ipv4.as_deref(), Some(address.as_str()));
            let diff = servers.diff(&server, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::UpToDate, "{:?}", diff.changes);

            let other_image = HetznerServer {
                image: "debian-99".to_string(),
                ..server.clone()
            };
            let diff = servers.diff(&other_image, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::HasChanges);
            assert!(diff.needs_recreate());
            assert_eq!(diff.changes.len(), 1);
            assert_eq!(diff.changes[0].name, "image");
            assert_eq!(diff.changes[0].expected.as_deref(), Some("debian-99"));
            assert_eq!(diff.changes[0].actual.as_deref(), Some("debian-13"));

            let other_keys = server
                .clone()
                .with_ssh_keys(vec![key_1.as_lookup(), key_2.as_lookup()]);
            let diff = servers.diff(&other_keys, &context).await.unwrap();
            assert_eq!(diff.status, DiffStatus::HasChanges);
            assert!(diff.needs_recreate());
            assert_eq!(diff.changes.len(), 1);
            assert_eq!(diff.changes[0].name, "ssh keys");
            assert_eq!(
                diff.changes[0].expected.as_deref(),
                Some("c5383689b3a31bbd0fafbe5c62865ae53a9da27adb1f96b1d94e358f83c9bab6")
            );
            assert_eq!(
                diff.changes[0].actual.as_deref(),
                Some("b500b5a03c1ddbd8b64d477d406ab9a11f985ad382d14fefa45541094599fcb9")
            );

            let other_user_data = HetznerServer {
                user_data: UserData::text("#cloud-config\nchanged\n"),
                ..server.clone()
            };
            let diff = servers.diff(&other_user_data, &context).await.unwrap();
            assert!(diff.needs_recreate());
            assert_eq!(diff.changes.len(), 1);
            assert_eq!(diff.changes[0].name, "user data checksum");

            // the recreate: the volume and the address survive the server
            assert!(
                servers
                    .destroy(&server.as_lookup(), &context)
                    .await
                    .unwrap()
            );
            assert!(
                servers
                    .lookup(&server.as_lookup(), &context)
                    .await
                    .unwrap()
                    .is_none()
            );
            assert!(
                ips.lookup(&ip.as_lookup(), &context)
                    .await
                    .unwrap()
                    .unwrap()
                    .assignee_id
                    .is_none()
            );
            let runtime = servers.apply(&other_user_data, &context).await.unwrap();
            assert_eq!(runtime.public_ipv4.as_deref(), Some(address.as_str()));
            assert_eq!(
                servers
                    .diff(&other_user_data, &context)
                    .await
                    .unwrap()
                    .status,
                DiffStatus::UpToDate
            );
            assert!(
                servers
                    .destroy(&server.as_lookup(), &context)
                    .await
                    .unwrap()
            );
            assert!(
                !servers
                    .destroy(&server.as_lookup(), &context)
                    .await
                    .unwrap()
            );
        })
        .await;
}
