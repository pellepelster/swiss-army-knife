//! the discovery of deployments against the real api: an ssh key and a firewall labeled
//! with a name make a deployment whose server is missing

mod support;

use sak_cloud::api::{DiffStatus, LookupProviders, Provisioner};
use sak_cloud::hetzner::NAME_LABEL;
use sak_cloud::hetzner::api::{Location, ServerType};
use sak_cloud::hetzner::deployment::{DeploymentStatus, ResourceKind, discover, state};
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
            let provider = HetznerProviderRuntime::from_api(
                hetzner.api.clone(),
                Location::hel1,
                ServerType::cx23,
            );
            let context = LookupProviders::new();
            let labels = hetzner.labels_with(&[(NAME_LABEL, &name)]);

            provider
                .ssh_keys()
                .apply(
                    &HetznerSshKey::new(&name, PUBLIC_KEY).with_labels(labels.clone()),
                    &context,
                )
                .await
                .unwrap();
            provider
                .firewalls()
                .apply(
                    &HetznerFirewall::new(&name, vec![inbound_icmp()]).with_labels(labels.clone()),
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

            let state = state(&provider, deployment).await.unwrap();
            assert_eq!(state.status, DeploymentStatus::Incomplete);
            let statuses: Vec<(ResourceKind, DiffStatus)> = state
                .resources
                .iter()
                .map(|resource| (resource.kind, resource.status))
                .collect();
            assert_eq!(
                statuses,
                vec![
                    (ResourceKind::SshKey, DiffStatus::UpToDate),
                    (ResourceKind::Firewall, DiffStatus::UpToDate),
                    (ResourceKind::Server, DiffStatus::Missing),
                ]
            );
        })
        .await;
}
