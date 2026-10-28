//! the whole thing against the real api: a backend declared as an ssh key, a volume, a
//! primary ip, a firewall and a server, diffed, applied, recreated after a change of its
//! user data and destroyed through the registry

mod support;

use sak_cloud::api::{Applied, DiffStatus, Resource};
use sak_cloud::hetzner::firewall::{HetznerFirewall, inbound_icmp, inbound_tcp};
use sak_cloud::hetzner::primary_ip::HetznerPrimaryIp;
use sak_cloud::hetzner::server::HetznerServer;
use sak_cloud::hetzner::ssh_key::HetznerSshKey;
use sak_cloud::hetzner::volume::HetznerVolume;
use sak_cloud::providers::hetzner::HetznerProviderRuntime;
use support::HetznerTest;

const PUBLIC_KEY: &str =
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE+u0dEVRZDzzp4E1teCqF49r8ig3YEk8eaPqNWfDcPb pelle@fry";

#[tokio::test]
async fn backend_flow() {
    let Some(hetzner) = HetznerTest::start() else {
        return;
    };

    hetzner
        .run(|hetzner| async move {
            let name = hetzner.test_id.clone();
            let location = hetzner.location();
            let server_type = hetzner.server_type();
            let provider =
                HetznerProviderRuntime::from_api(hetzner.api.clone(), location, server_type);

            let declare = |user_data: &str| {
                let key = HetznerSshKey::new(&name, PUBLIC_KEY).with_labels(hetzner.labels.clone());
                let volume = HetznerVolume::new(&name, location, 16)
                    .with_protected(false)
                    .with_labels(hetzner.labels.clone());
                let ip = HetznerPrimaryIp::ipv4(&name, location)
                    .with_protected(false)
                    .with_labels(hetzner.labels.clone());
                let firewall = HetznerFirewall::new(&name, vec![inbound_tcp("22"), inbound_icmp()])
                    .applied_to(hetzner.labels.clone())
                    .with_labels(hetzner.labels.clone());
                let server = HetznerServer::new(&name, location, server_type, user_data)
                    .with_ssh_keys(vec![key.as_lookup()])
                    .with_volumes(vec![volume.as_lookup()])
                    .with_ipv4(ip.as_lookup())
                    .with_labels(hetzner.labels.clone());
                let mut registry = provider.registry();
                // the server first on purpose, the registry orders by dependencies
                registry.add(server).unwrap();
                registry.add(firewall).unwrap();
                registry.add(key).unwrap();
                registry.add(volume).unwrap();
                registry.add(ip).unwrap();
                registry
            };

            let mut registry = declare("#cloud-config\n");
            let diffs = registry.diff_all().await.unwrap();
            let statuses: Vec<DiffStatus> = diffs.iter().map(|diff| diff.status).collect();
            assert_eq!(
                statuses,
                vec![
                    DiffStatus::Missing,
                    DiffStatus::Missing,
                    DiffStatus::Missing,
                    DiffStatus::ParentMissing,
                    DiffStatus::Missing
                ],
                "{diffs:?}"
            );
            assert_eq!(diffs[3].resource.log_text, format!("server '{name}'"));

            let results = registry.apply_all(false).await.unwrap();
            let order: Vec<String> = results
                .iter()
                .map(|result| result.resource.log_text.clone())
                .collect();
            assert_eq!(
                order,
                vec![
                    format!("ssh key '{name}'"),
                    format!("volume '{name}'"),
                    format!("primary ip '{name}'"),
                    format!("server '{name}'"),
                    format!("firewall '{name}'"),
                ]
            );
            assert!(
                results
                    .iter()
                    .all(|result| result.applied == Applied::Created)
            );

            let diffs = registry.diff_all().await.unwrap();
            assert!(
                diffs.iter().all(|diff| diff.status == DiffStatus::UpToDate),
                "{diffs:?}"
            );
            let results = registry.apply_all(false).await.unwrap();
            assert!(
                results
                    .iter()
                    .all(|result| result.applied == Applied::Unchanged)
            );

            let mut changed = declare("#cloud-config\npackage_update: true\n");
            let diffs = changed.diff_all().await.unwrap();
            let server = diffs
                .iter()
                .find(|diff| diff.resource.log_text.starts_with("server"))
                .unwrap();
            assert_eq!(server.status, DiffStatus::HasChanges);
            assert!(server.needs_recreate());
            assert_eq!(server.changes[0].name, "user data checksum");

            let error = changed.apply_all(false).await.unwrap_err();
            assert!(error.to_string().contains("needs a recreate"), "{error}");

            let results = changed.apply_all(true).await.unwrap();
            let server = results
                .iter()
                .find(|result| result.resource.log_text.starts_with("server"))
                .unwrap();
            assert_eq!(server.applied, Applied::Recreated);
            assert_eq!(
                results
                    .iter()
                    .filter(|result| result.applied == Applied::Unchanged)
                    .count(),
                4
            );
            let diffs = changed.diff_all().await.unwrap();
            assert!(
                diffs.iter().all(|diff| diff.status == DiffStatus::UpToDate),
                "{diffs:?}"
            );

            let destroyed = changed.destroy_all().await.unwrap();
            assert_eq!(destroyed.len(), 5);
            assert_eq!(destroyed[0].log_text, format!("firewall '{name}'"));
            assert_eq!(destroyed[1].log_text, format!("server '{name}'"));
            let diffs = changed.diff_all().await.unwrap();
            assert!(diffs.iter().all(|diff| matches!(
                diff.status,
                DiffStatus::Missing | DiffStatus::ParentMissing
            )));
        })
        .await;
}
