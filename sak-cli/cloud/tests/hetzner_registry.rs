//! the whole thing against the real api: a deployment as the wizard declares it, an ssh
//! key, a data volume, a primary ipv4 and ipv6, a firewall and a server, diffed, applied,
//! recreated after a change of its user data and destroyed through the registry

mod support;

use sak_cloud::api::{Applied, DiffStatus, Resource};
use sak_cloud::hetzner::deployment::DeploymentSpec;
use sak_cloud::hetzner::deployment::RESOURCE_PREFIX;
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
            // the cloud names of the deployment, sak-<name>
            let cloud_name = format!("{RESOURCE_PREFIX}{name}");
            let location = hetzner.location();
            let server_type = hetzner.server_type();
            let provider = HetznerProviderRuntime::from_api(hetzner.api.clone());

            let declare = |user_data: &str, recreatable: bool| {
                let mut spec = DeploymentSpec::new(&name, PUBLIC_KEY, location, server_type)
                    .unwrap()
                    .with_password_hash("0123456789abcdef")
                    .with_labels(hetzner.labels.clone());
                spec.user_data = user_data.to_string();
                let mut registry = provider.registry().owned_by(spec.identity());
                // the server first on purpose, the registry orders by dependencies
                registry.add(spec.server()).unwrap();
                registry.add(spec.firewall()).unwrap();
                registry.add(spec.ssh_key()).unwrap();
                registry.add(spec.data_volume()).unwrap();
                registry.add(spec.ipv4()).unwrap();
                registry.add(spec.ipv6()).unwrap();
                if recreatable {
                    registry = registry.recreatable(spec.server().resource_ref());
                }
                registry
            };

            let mut registry = declare("#cloud-config\n", true);
            let diffs = registry.diff_all().await.unwrap();
            let statuses: Vec<DiffStatus> = diffs.iter().map(|diff| diff.status).collect();
            assert_eq!(
                statuses,
                vec![
                    DiffStatus::Missing,
                    DiffStatus::Missing,
                    DiffStatus::Missing,
                    DiffStatus::Missing,
                    DiffStatus::ParentMissing,
                    DiffStatus::Missing
                ],
                "{diffs:?}"
            );
            assert_eq!(diffs[4].resource.log_text, format!("server '{cloud_name}'"));

            let results = registry.apply_all().await.unwrap();
            let order: Vec<String> = results
                .iter()
                .map(|result| result.resource.log_text.clone())
                .collect();
            assert_eq!(
                order,
                vec![
                    format!("ssh key '{cloud_name}'"),
                    format!("volume '{cloud_name}-data'"),
                    format!("primary ip '{cloud_name}-ipv4'"),
                    format!("primary ip '{cloud_name}-ipv6'"),
                    format!("server '{cloud_name}'"),
                    format!("firewall '{cloud_name}'"),
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
            let results = registry.apply_all().await.unwrap();
            assert!(
                results
                    .iter()
                    .all(|result| result.applied == Applied::Unchanged)
            );

            // the server is not recreatable unless declared so: the plan refuses it
            let mut refused = declare("#cloud-config\npackage_update: true\n", false);
            let error = refused.diff_all().await.unwrap_err();
            assert!(
                matches!(error, sak_cloud::api::Error::RecreateRefused { .. }),
                "{error}"
            );

            let mut changed = declare("#cloud-config\npackage_update: true\n", true);
            let diffs = changed.diff_all().await.unwrap();
            let server = diffs
                .iter()
                .find(|diff| diff.resource.log_text.starts_with("server"))
                .unwrap();
            assert_eq!(server.status, DiffStatus::HasChanges);
            assert!(server.needs_recreate());
            assert_eq!(server.changes[0].name, "user data checksum");

            let results = changed.apply_all().await.unwrap();
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
                5
            );
            let diffs = changed.diff_all().await.unwrap();
            assert!(
                diffs.iter().all(|diff| diff.status == DiffStatus::UpToDate),
                "{diffs:?}"
            );

            let destroyed = changed.destroy_all().await.unwrap();
            assert_eq!(destroyed.len(), 6);
            assert_eq!(destroyed[0].log_text, format!("firewall '{cloud_name}'"));
            assert_eq!(destroyed[1].log_text, format!("server '{cloud_name}'"));
            let diffs = changed.diff_all().await.unwrap();
            assert!(diffs.iter().all(|diff| matches!(
                diff.status,
                DiffStatus::Missing | DiffStatus::ParentMissing
            )));
        })
        .await;
}
