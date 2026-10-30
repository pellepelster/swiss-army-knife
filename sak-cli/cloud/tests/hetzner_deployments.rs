//! the discovery of deployments against the real api: an ssh key and a firewall labeled
//! with a name make a deployment whose volume, addresses and server are missing

mod support;

use sak_cloud::api::{DiffStatus, LookupProviders, Provisioner};
use sak_cloud::hetzner::NAME_LABEL;
use sak_cloud::hetzner::deployment::{
    DeploymentStatus, RESOURCE_PREFIX, ResourceKind, discover, state,
};
use sak_cloud::hetzner::firewall::{HetznerFirewall, inbound_icmp};
use sak_cloud::hetzner::ssh_key::HetznerSshKey;
use sak_cloud::providers::hetzner::HetznerProviderRuntime;
use support::HetznerTest;

const PUBLIC_KEY: &str =
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE+u0dEVRZDzzp4E1teCqF49r8ig3YEk8eaPqNWfDcPb pelle@fry";

#[tokio::test]
async fn discovers_a_deployment_by_its_name_label() {
    let Some(hetzner) = HetznerTest::start() else {
        return;
    };

    hetzner
        .run(|hetzner| async move {
            let name = hetzner.test_id.clone();
            let provider = HetznerProviderRuntime::from_api(hetzner.api.clone());
            let context = LookupProviders::new();
            let labels = hetzner.labels_with(&[(NAME_LABEL, &name)]);
            // the cloud names of a deployment, sak-<name>
            let cloud_name = format!("{RESOURCE_PREFIX}{name}");

            provider
                .ssh_keys()
                .apply(
                    &HetznerSshKey::new(&cloud_name, PUBLIC_KEY).with_labels(labels.clone()),
                    &context,
                )
                .await
                .unwrap();
            provider
                .firewalls()
                .apply(
                    &HetznerFirewall::new(&cloud_name, vec![inbound_icmp()])
                        .with_labels(labels.clone()),
                    &context,
                )
                .await
                .unwrap();

            let deployments = discover(&hetzner.api).await.unwrap();
            let deployment = deployments
                .iter()
                .find(|deployment| deployment.name == name)
                .expect("the deployment is found");
            assert!(deployment.ssh_key.is_some());
            assert!(deployment.firewall.is_some());
            assert!(deployment.server.is_none());

            let state = state(&provider, deployment).await;
            assert_eq!(state.status, DeploymentStatus::Incomplete);
            // every resource a deploy declares shows: the key and the firewall are there (the
            // firewall with other rules than a deploy declares), the rest is missing
            let kinds: Vec<ResourceKind> = state
                .resources
                .iter()
                .map(|resource| resource.kind)
                .collect();
            assert_eq!(
                kinds,
                vec![
                    ResourceKind::SshKey,
                    ResourceKind::Volume,
                    ResourceKind::PrimaryIp,
                    ResourceKind::PrimaryIp,
                    ResourceKind::Firewall,
                    ResourceKind::Server,
                ]
            );
            let status = |kind| {
                state
                    .resources
                    .iter()
                    .filter(|resource| resource.kind == kind)
                    .filter_map(|resource| resource.status)
                    .collect::<Vec<_>>()
            };
            assert!(!status(ResourceKind::SshKey).contains(&DiffStatus::Missing));
            assert!(!status(ResourceKind::Firewall).contains(&DiffStatus::Missing));
            assert_eq!(status(ResourceKind::Volume), vec![DiffStatus::Missing]);
            assert_eq!(
                status(ResourceKind::PrimaryIp),
                vec![DiffStatus::Missing, DiffStatus::Missing]
            );
            assert_eq!(
                status(ResourceKind::Server),
                vec![DiffStatus::ParentMissing]
            );
        })
        .await;
}
