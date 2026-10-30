//! a deployment compared with what a deploy declares, for the list of deployments

use crate::api::{DiffStatus, ResourceRef, Result};
use crate::hetzner::api::Location;
use crate::hetzner::deployment::spec::ObservedSpec;
use crate::hetzner::deployment::{DiscoveredDeployment, DnsMode};
use crate::hetzner::dns_record::HetznerDnsRecordLookup;
use crate::hetzner::firewall::HetznerFirewallLookup;
use crate::hetzner::primary_ip::HetznerPrimaryIpLookup;
use crate::hetzner::server::HetznerServerLookup;
use crate::hetzner::ssh_key::HetznerSshKeyLookup;
use crate::hetzner::volume::HetznerVolumeLookup;
use crate::providers::hetzner::HetznerProviderRuntime;
use serde::Serialize;

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ResourceKind {
    SshKey,
    Volume,
    PrimaryIp,
    Firewall,
    Server,
    DnsRecord,
}

impl ResourceKind {
    /// the kind of a declared resource, by the lookup type of its reference
    pub fn of(resource: &ResourceRef) -> Option<ResourceKind> {
        use std::any::TypeId;
        let kinds = [
            (TypeId::of::<HetznerSshKeyLookup>(), ResourceKind::SshKey),
            (TypeId::of::<HetznerVolumeLookup>(), ResourceKind::Volume),
            (
                TypeId::of::<HetznerPrimaryIpLookup>(),
                ResourceKind::PrimaryIp,
            ),
            (
                TypeId::of::<HetznerFirewallLookup>(),
                ResourceKind::Firewall,
            ),
            (TypeId::of::<HetznerServerLookup>(), ResourceKind::Server),
            (
                TypeId::of::<HetznerDnsRecordLookup>(),
                ResourceKind::DnsRecord,
            ),
        ];
        kinds
            .into_iter()
            .find(|(type_id, _)| *type_id == resource.type_id)
            .map(|(_, kind)| kind)
    }
}

/// the state of one resource of the group
#[derive(Clone, Debug, Serialize)]
pub struct ResourceState {
    pub kind: ResourceKind,
    pub name: String,
    /// none when the deployment could not be compared (`DeploymentStatus::Unknown`): the
    /// resource was found, nothing more is known
    pub status: Option<DiffStatus>,
    /// the changes, as log texts of the diff
    pub changes: Vec<String>,
    /// a short summary, e.g. the address of a server or the fingerprint of a key (`detail`)
    pub detail: Option<String>,
}

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DeploymentStatus {
    /// every resource of the group exists as declared
    Ok,
    /// a resource of the group is missing
    Incomplete,
    /// a resource differs from its declaration
    Changed,
    /// the deployment could not be compared with its declaration, `DeploymentState::error` says
    /// why
    Unknown,
}

/// a deployment compared with its declared resource group
#[derive(Clone, Debug, Serialize)]
pub struct DeploymentState {
    pub name: String,
    pub status: DeploymentStatus,
    /// the location of the server, else of a volume or a primary ip; none without them
    pub location: Option<Location>,
    /// the dns mode on its resources; none without it
    pub dns_mode: Option<DnsMode>,
    pub resources: Vec<ResourceState>,
    /// why the deployment could not be compared, with `DeploymentStatus::Unknown`
    pub error: Option<String>,
}

/// the diff of a deployment: every resource that a deploy declares (ssh key, data volume,
/// primary ips, firewall, server and dns records), declared from the labels and settings of the
/// deployment (`ObservedSpec`) and diffed as a plan does. What a deploy would
/// create is missing; what it would change shows its changes. A deployment that cannot be
/// compared (resources in two locations, a resource of its name that belongs to someone else, a
/// failed request) is `Unknown` with the error and the resources found; it never fails a list of
/// deployments
pub async fn state(
    provider: &HetznerProviderRuntime,
    deployment: &DiscoveredDeployment,
) -> DeploymentState {
    match compare(provider, deployment).await {
        Ok(mut resources) => {
            resources.extend(left_out(deployment));
            let status = if resources.iter().any(|resource| {
                matches!(
                    resource.status,
                    Some(DiffStatus::Missing | DiffStatus::ParentMissing)
                )
            }) {
                DeploymentStatus::Incomplete
            } else if resources
                .iter()
                .any(|resource| resource.status != Some(DiffStatus::UpToDate))
            {
                DeploymentStatus::Changed
            } else {
                DeploymentStatus::Ok
            };
            deployment_state(deployment, status, resources, None)
        }
        Err(error) => deployment_state(
            deployment,
            DeploymentStatus::Unknown,
            found(deployment)
                .into_iter()
                .chain(left_out(deployment))
                .collect(),
            Some(error.to_string()),
        ),
    }
}

/// every resource a deploy declares, with its diff
async fn compare(
    provider: &HetznerProviderRuntime,
    deployment: &DiscoveredDeployment,
) -> Result<Vec<ResourceState>> {
    // a list applies nothing: a duplicate or a refused recreate shows, it does not fail
    let diffs = ObservedSpec::of(deployment)?.compare(provider).await?;
    Ok(diffs
        .into_iter()
        .filter_map(|diff| {
            let kind = ResourceKind::of(&diff.resource)?;
            let detail = detail(deployment, kind, &diff.resource.name);
            let name = diff.resource.name;
            Some(ResourceState {
                kind,
                name,
                status: Some(diff.status),
                // a duplicate says which resource it duplicates
                changes: diff
                    .changes
                    .iter()
                    .map(|change| change.log_text())
                    .chain(diff.duplicate_message.clone())
                    .collect(),
                detail,
            })
        })
        .collect())
}

/// the resources a deployment has a second of, as duplicates: a deploy uses the first, the user
/// removes the others
fn left_out(deployment: &DiscoveredDeployment) -> Vec<ResourceState> {
    deployment
        .left_out
        .iter()
        .map(|left_out| ResourceState {
            kind: left_out.kind,
            name: left_out.name.clone(),
            status: Some(DiffStatus::Duplicate),
            changes: vec![format!(
                "{} is a second one of the deployment, a deploy leaves it alone",
                left_out.name
            )],
            detail: Some("duplicate".to_string()),
        })
        .collect()
}

/// the resources of a deployment as they were found, not compared
fn found(deployment: &DiscoveredDeployment) -> Vec<ResourceState> {
    let mut found: Vec<(ResourceKind, &str)> = Vec::new();
    found.extend(
        deployment
            .ssh_key
            .iter()
            .map(|key| (ResourceKind::SshKey, key.name.as_str())),
    );
    found.extend(
        deployment
            .data_volume
            .iter()
            .map(|volume| (ResourceKind::Volume, volume.name.as_str())),
    );
    found.extend(
        deployment
            .primary_ips()
            .map(|ip| (ResourceKind::PrimaryIp, ip.name.as_str())),
    );
    found.extend(
        deployment
            .firewall
            .iter()
            .map(|firewall| (ResourceKind::Firewall, firewall.name.as_str())),
    );
    found.extend(
        deployment
            .server
            .iter()
            .map(|server| (ResourceKind::Server, server.name.as_str())),
    );
    found
        .into_iter()
        .map(|(kind, name)| ResourceState {
            kind,
            name: name.to_string(),
            status: None,
            changes: Vec::new(),
            detail: detail(deployment, kind, name),
        })
        .collect()
}

fn deployment_state(
    deployment: &DiscoveredDeployment,
    status: DeploymentStatus,
    resources: Vec<ResourceState>,
    error: Option<String>,
) -> DeploymentState {
    DeploymentState {
        name: deployment.name.clone(),
        status,
        // of the server, else of a volume or a primary ip; the first when they differ, the error
        // of the state names them
        location: deployment
            .locations()
            .first()
            .map(|(_, location)| *location),
        dns_mode: deployment.dns_mode(),
        resources,
        error,
    }
}

/// a short summary of a resource: the fingerprint of the key, the size of the volume, the
/// address of a primary ip, the number of firewall rules, the type, location and address of the
/// server
fn detail(deployment: &DiscoveredDeployment, kind: ResourceKind, name: &str) -> Option<String> {
    match kind {
        ResourceKind::SshKey => deployment
            .ssh_key
            .as_ref()
            .filter(|key| key.name == name)
            .map(|key| key.fingerprint.clone()),
        ResourceKind::Volume => deployment
            .data_volume
            .as_ref()
            .filter(|volume| volume.name == name)
            .map(|volume| format!("{} GB", volume.size_gb)),
        ResourceKind::PrimaryIp => deployment
            .primary_ips()
            .find(|ip| ip.name == name)
            .map(|ip| ip.ip.clone()),
        ResourceKind::Firewall => deployment
            .firewall
            .as_ref()
            .filter(|firewall| firewall.name == name)
            .map(|firewall| format!("{} rules", firewall.rules.len())),
        ResourceKind::Server => deployment
            .server
            .as_ref()
            .filter(|server| server.name == name)
            .map(|server| {
                format!(
                    "{} in {}{}",
                    server.server_type,
                    server.location,
                    server
                        .public_ipv4
                        .as_ref()
                        .map(|ip| format!(", {ip}"))
                        .unwrap_or_default()
                )
            }),
        ResourceKind::DnsRecord => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::DiffStatus;
    use crate::hetzner::deployment::fixtures::*;
    use crate::hetzner::deployment::*;
    use crate::hetzner::test_support::mock_api_all;
    use crate::hetzner::{DNS_MODE_LABEL, NAME_LABEL, ROOT_DOMAIN_LABEL};
    use crate::providers::hetzner::HetznerProviderRuntime;
    use serde_json::json;

    #[tokio::test]
    async fn a_deployment_that_kept_only_its_volume_is_incomplete() {
        // a destroy without --delete-data keeps the volume with the name label, and no ssh key
        let (_server, api) = mock_api_all(&[
            ("servers", vec![]),
            ("ssh_keys", vec![]),
            ("firewalls", vec![]),
            (
                "volumes",
                vec![volume(3, "sak-gamma-data", "nbg1", named("gamma"))],
            ),
            ("primary_ips", vec![]),
        ])
        .await;
        let provider = HetznerProviderRuntime::from_api(api.clone());
        let deployments = discover(&api).await.unwrap();

        let gamma = state(&provider, &deployments[0]).await;

        assert_eq!(
            gamma.status,
            DeploymentStatus::Incomplete,
            "{:?}",
            gamma.error
        );
        assert_eq!(gamma.error, None);
        let key = gamma
            .resources
            .iter()
            .find(|resource| resource.kind == ResourceKind::SshKey)
            .unwrap();
        assert_eq!(key.status, Some(DiffStatus::Missing));
    }

    #[tokio::test]
    async fn a_deployment_that_cannot_be_compared_is_unknown_with_what_was_found() {
        // the volume and a primary ip in two locations: a deploy must not guess the location
        let (_server, api) = mock_api_all(&[
            ("servers", vec![]),
            ("ssh_keys", vec![key(8, "sak-delta", named("delta"))]),
            ("firewalls", vec![]),
            (
                "volumes",
                vec![volume(3, "sak-delta-data", "nbg1", named("delta"))],
            ),
            (
                "primary_ips",
                vec![ip(11, "sak-delta-ipv4", "ipv4", "hel1", named("delta"))],
            ),
        ])
        .await;
        let provider = HetznerProviderRuntime::from_api(api.clone());
        let deployments = discover(&api).await.unwrap();

        let delta = state(&provider, &deployments[0]).await;

        assert_eq!(delta.status, DeploymentStatus::Unknown);
        let error = delta.error.unwrap();
        assert!(error.contains("hel1") && error.contains("nbg1"), "{error}");
        let found: Vec<(ResourceKind, &str, Option<DiffStatus>)> = delta
            .resources
            .iter()
            .map(|resource| (resource.kind, resource.name.as_str(), resource.status))
            .collect();
        assert_eq!(
            found,
            vec![
                (ResourceKind::SshKey, "sak-delta", None),
                (ResourceKind::Volume, "sak-delta-data", None),
                (ResourceKind::PrimaryIp, "sak-delta-ipv4", None),
            ]
        );
        assert_eq!(delta.resources[1].detail.as_deref(), Some("16 GB"));
    }

    #[tokio::test]
    async fn the_state_shows_every_resource_a_deploy_declares() {
        let (_server, api) = project().await;
        let provider = HetznerProviderRuntime::from_api(api.clone());
        let deployments = discover(&api).await.unwrap();

        // beta has only its ssh key: all other resources that a deploy declares are missing
        let beta = state(&provider, &deployments[1]).await;
        assert_eq!(beta.status, DeploymentStatus::Incomplete);
        let resources: Vec<(ResourceKind, &str, DiffStatus)> = beta
            .resources
            .iter()
            .map(|resource| {
                (
                    resource.kind,
                    resource.name.as_str(),
                    resource.status.unwrap(),
                )
            })
            .collect();
        assert_eq!(
            resources,
            vec![
                (ResourceKind::SshKey, "sak-beta", DiffStatus::UpToDate),
                (ResourceKind::Volume, "sak-beta-data", DiffStatus::Missing),
                (
                    ResourceKind::PrimaryIp,
                    "sak-beta-ipv4",
                    DiffStatus::Missing
                ),
                (
                    ResourceKind::PrimaryIp,
                    "sak-beta-ipv6",
                    DiffStatus::Missing
                ),
                (ResourceKind::Firewall, "sak-beta", DiffStatus::Missing),
                (ResourceKind::Server, "sak-beta", DiffStatus::ParentMissing),
            ]
        );
    }

    #[tokio::test]
    async fn the_state_shows_the_dns_records_of_a_deployment_with_a_root_domain() {
        let labels =
            json!({ NAME_LABEL: "alpha", ROOT_DOMAIN_LABEL: "pelle.io", DNS_MODE_LABEL: "domain" });
        let (_server, api) = mock_api_all(&[
            ("servers", vec![]),
            ("ssh_keys", vec![key(7, "sak-alpha", labels)]),
            ("firewalls", vec![]),
            ("volumes", vec![]),
            ("primary_ips", vec![]),
        ])
        .await;
        let provider = HetznerProviderRuntime::from_api(api.clone());
        let deployments = discover(&api).await.unwrap();

        let alpha = state(&provider, &deployments[0]).await;

        let records: Vec<&str> = alpha
            .resources
            .iter()
            .filter(|resource| resource.kind == ResourceKind::DnsRecord)
            .map(|resource| resource.name.as_str())
            .collect();
        // @ and * of the domain mode, each an A and an AAAA record
        assert_eq!(records.len(), 4, "{:?}", alpha.resources);
    }

    #[tokio::test]
    async fn a_server_created_with_another_key_is_a_change() {
        let mut other_key = key(7, "sak-alpha", named("alpha"));
        other_key["fingerprint"] = json!("aa:bb");
        let (_server, api) = mock_api_all(&[
            ("servers", vec![server(1, "sak-alpha", named("alpha"))]),
            ("ssh_keys", vec![other_key]),
            ("firewalls", vec![firewall(5, "sak-alpha", named("alpha"))]),
            (
                "volumes",
                vec![json!({
                    "id": 3, "name": "sak-alpha-data", "format": "ext4", "linux_device": "/dev/x", "size": 16,
                    "protection": { "delete": true }, "server": 1, "status": "available",
                    "location": { "id": 3, "name": "hel1" }, "labels": named("alpha")
                })],
            ),
            (
                "primary_ips",
                ["ipv4", "ipv6"]
                    .iter()
                    .enumerate()
                    .map(|(index, ip_type)| {
                        json!({
                            "id": 11 + index, "name": format!("sak-alpha-{ip_type}"), "ip": "1.2.3.4", "type": ip_type,
                            "assignee_id": 1, "assignee_type": "server", "auto_delete": false,
                            "protection": { "delete": true }, "location": { "id": 3, "name": "hel1" },
                            "labels": named("alpha")
                        })
                    })
                    .collect(),
            ),
        ])
        .await;
        let provider = HetznerProviderRuntime::from_api(api.clone());
        let deployments = discover(&api).await.unwrap();

        let alpha = state(&provider, &deployments[0]).await;

        // the labels of the server show that it was created with another key
        assert_eq!(
            alpha.status,
            DeploymentStatus::Changed,
            "{:?}",
            alpha.resources
        );
        let server = alpha
            .resources
            .iter()
            .find(|resource| resource.kind == ResourceKind::Server)
            .unwrap();
        assert!(
            server
                .changes
                .iter()
                .any(|change| change.starts_with("ssh keys should be")),
            "{:?}",
            server.changes
        );
        assert_eq!(server.detail.as_deref(), Some("cx23 in hel1, 1.2.3.4"));
    }

    #[tokio::test]
    async fn a_key_that_was_renamed_is_a_duplicate_that_names_it_and_its_server_misses_it() {
        let (_server, api) = mock_api_all(&[
            ("servers", vec![server(1, "sak-alpha", named("alpha"))]),
            // renamed in the console: a deploy would create the key anew, Hetzner refuses it
            ("ssh_keys", vec![key(7, "sak-alpha-old", named("alpha"))]),
            ("firewalls", vec![firewall(5, "sak-alpha", named("alpha"))]),
            (
                "volumes",
                vec![volume(3, "sak-alpha-data", "hel1", named("alpha"))],
            ),
            (
                "primary_ips",
                vec![
                    ip(11, "sak-alpha-ipv4", "ipv4", "hel1", named("alpha")),
                    ip(12, "sak-alpha-ipv6", "ipv6", "hel1", named("alpha")),
                ],
            ),
        ])
        .await;
        let provider = HetznerProviderRuntime::from_api(api.clone());
        let deployments = discover(&api).await.unwrap();

        let alpha = state(&provider, &deployments[0]).await;

        // the key is a duplicate that names the renamed one, the server misses its key
        assert_eq!(
            alpha.status,
            DeploymentStatus::Incomplete,
            "{:?}",
            alpha.error
        );
        let key = alpha
            .resources
            .iter()
            .find(|resource| resource.kind == ResourceKind::SshKey && resource.name == "sak-alpha")
            .unwrap();
        assert_eq!(key.status, Some(DiffStatus::Duplicate));
        assert!(
            key.changes
                .iter()
                .any(|change| change.contains("sak-alpha-old")),
            "{:?}",
            key.changes
        );
        let server = alpha
            .resources
            .iter()
            .find(|resource| resource.kind == ResourceKind::Server)
            .unwrap();
        assert_eq!(server.status, Some(DiffStatus::ParentMissing));
    }

    #[test]
    fn kinds_and_statuses_are_snake_case_in_json() {
        assert_eq!(
            serde_json::to_value(ResourceKind::DnsRecord).unwrap(),
            json!("dns_record")
        );
        assert_eq!(
            serde_json::to_value(DeploymentStatus::Changed).unwrap(),
            json!("changed")
        );
    }
}
