//! the sak deployments of a cloud project: everything carrying the name label, grouped by
//! its value, and the state of each against the resource group a deployment consists of

use crate::api::{
    DiffStatus, Error, Labels, LookupProviders, Provisioner, Registry, Resource, ResourceDiff,
    Result,
};
use crate::hetzner::api::{HetznerApi, ListQuery, Location, ServerType};
use crate::hetzner::firewall::{
    HetznerFirewall, HetznerFirewallRuntime, inbound_icmp, inbound_tcp,
};
use crate::hetzner::labels::HetznerLabels;
use crate::hetzner::primary_ip::{
    HetznerPrimaryIp, HetznerPrimaryIpLookup, HetznerPrimaryIpRuntime,
};
use crate::hetzner::server::{HetznerServer, HetznerServerRuntime, UserData};
use crate::hetzner::ssh_key::{HetznerSshKey, HetznerSshKeyLookup, HetznerSshKeyRuntime};
use crate::hetzner::volume::{HetznerVolume, HetznerVolumeLookup, HetznerVolumeRuntime};
use crate::hetzner::{NAME_LABEL, USER_DATA_LABEL, is_managed_label};
use crate::providers::hetzner::HetznerProviderRuntime;
use serde::Serialize;
use std::collections::BTreeMap;
use tracing::warn;

/// what the cloud holds of a deployment: the resources labeled with its name
#[derive(Clone, Debug, Default)]
pub struct DiscoveredDeployment {
    pub name: String,
    pub ssh_key: Option<HetznerSshKeyRuntime>,
    pub firewall: Option<HetznerFirewallRuntime>,
    pub server: Option<HetznerServerRuntime>,
    pub volumes: Vec<HetznerVolumeRuntime>,
    pub primary_ips: Vec<HetznerPrimaryIpRuntime>,
}

impl DiscoveredDeployment {
    fn new(name: &str) -> Self {
        DiscoveredDeployment {
            name: name.to_string(),
            ..Default::default()
        }
    }
}

/// the deployments of the project, by name; a deployment has one server, ssh key and
/// firewall, a second one with the same name label is left out with a warning
pub async fn discover(api: &HetznerApi) -> Result<Vec<DiscoveredDeployment>> {
    let query = ListQuery::new().label_exists(NAME_LABEL);
    let mut deployments: BTreeMap<String, DiscoveredDeployment> = BTreeMap::new();

    for server in api.servers().list(&query).await? {
        let Some(deployment) = of(&mut deployments, &server.labels) else {
            continue;
        };
        if deployment.server.is_some() {
            warn!(
                "deployment '{}' has more than one server, '{}' is left out",
                deployment.name, server.name
            );
            continue;
        }
        deployment.server = Some(server.into());
    }
    for key in api.ssh_keys().list(&query).await? {
        let Some(deployment) = of(&mut deployments, &key.labels) else {
            continue;
        };
        if deployment.ssh_key.is_some() {
            warn!(
                "deployment '{}' has more than one ssh key, '{}' is left out",
                deployment.name, key.name
            );
            continue;
        }
        deployment.ssh_key = Some(key.into());
    }
    for firewall in api.firewalls().list(&query).await? {
        let Some(deployment) = of(&mut deployments, &firewall.labels) else {
            continue;
        };
        if deployment.firewall.is_some() {
            warn!(
                "deployment '{}' has more than one firewall, '{}' is left out",
                deployment.name, firewall.name
            );
            continue;
        }
        deployment.firewall = Some(HetznerFirewallRuntime::from_firewall(firewall)?);
    }
    for volume in api.volumes().list(&query).await? {
        if let Some(deployment) = of(&mut deployments, &volume.labels) {
            deployment.volumes.push(volume.into());
        }
    }
    for ip in api.primary_ips().list(&query).await? {
        if let Some(deployment) = of(&mut deployments, &ip.labels) {
            deployment.primary_ips.push(ip.into());
        }
    }
    Ok(deployments.into_values().collect())
}

/// the deployment the labels belong to, created on first sight; none without the name label
fn of<'a>(
    deployments: &'a mut BTreeMap<String, DiscoveredDeployment>,
    labels: &Labels,
) -> Option<&'a mut DiscoveredDeployment> {
    let name = labels.get(NAME_LABEL)?;
    Some(
        deployments
            .entry(name.clone())
            .or_insert_with(|| DiscoveredDeployment::new(name)),
    )
}

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ResourceKind {
    SshKey,
    Firewall,
    Server,
}

/// the state of one resource of the group
#[derive(Clone, Debug, Serialize)]
pub struct ResourceState {
    pub kind: ResourceKind,
    pub name: String,
    pub status: DiffStatus,
    /// the changes, as they read in a diff
    pub changes: Vec<String>,
    /// what there is to know at a glance: the address of a server, the fingerprint of a key
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
}

/// a deployment against the resource group it consists of
#[derive(Clone, Debug, Serialize)]
pub struct DeploymentState {
    pub name: String,
    pub status: DeploymentStatus,
    pub resources: Vec<ResourceState>,
}

/// the diff of the resource group of a deployment: its ssh key, firewall and server,
/// each declared from what the cloud holds of it, so what shows up is what is missing
/// and whether the server was created with the deployment's key
pub async fn state(
    provider: &HetznerProviderRuntime,
    deployment: &DiscoveredDeployment,
) -> Result<DeploymentState> {
    let context = LookupProviders::new()
        .with::<HetznerSshKeyLookup, _>(provider.ssh_keys())
        .with::<HetznerVolumeLookup, _>(provider.volumes())
        .with::<HetznerPrimaryIpLookup, _>(provider.primary_ips());
    let name = &deployment.name;
    let mut resources = Vec::new();

    let ssh_key = match &deployment.ssh_key {
        Some(key) => {
            let resource =
                HetznerSshKey::new(&key.name, &key.public_key).with_labels(key.labels.clone());
            let diff = provider.ssh_keys().diff(&resource, &context).await?;
            resource_state(
                ResourceKind::SshKey,
                &key.name,
                diff,
                Some(key.fingerprint.clone()),
            )
        }
        None => missing(ResourceKind::SshKey, name),
    };
    resources.push(ssh_key);

    let firewall = match &deployment.firewall {
        Some(firewall) => {
            let resource = HetznerFirewall::new(&firewall.name, firewall.rules.clone())
                .applied_to(firewall.applied_to_labels.clone())
                .with_labels(firewall.labels.clone());
            let diff = provider.firewalls().diff(&resource, &context).await?;
            let detail = format!("{} rules", firewall.rules.len());
            resource_state(ResourceKind::Firewall, &firewall.name, diff, Some(detail))
        }
        None => missing(ResourceKind::Firewall, name),
    };
    resources.push(firewall);

    let server = match &deployment.server {
        Some(server) => {
            let labels = HetznerLabels::from_raw(server.labels.clone());
            let user_data = labels
                .labels()
                .get(USER_DATA_LABEL)
                .cloned()
                .unwrap_or_default();
            let declared: Labels = server
                .labels
                .iter()
                .filter(|(key, _)| !is_managed_label(key))
                .map(|(key, value)| (key.clone(), value.clone()))
                .collect();
            let volumes = deployment
                .volumes
                .iter()
                .filter(|volume| server.volumes.contains(&volume.id))
                .map(|volume| HetznerVolumeLookup::new(&volume.name))
                .collect();
            let ipv4 = deployment
                .primary_ips
                .iter()
                .find(|ip| Some(ip.id) == server.ipv4_id)
                .map(|ip| HetznerPrimaryIpLookup::new(&ip.name));
            let mut resource =
                HetznerServer::new(&server.name, server.location, server.server_type, "")
                    .with_image(&server.image)
                    .with_ssh_keys(
                        deployment
                            .ssh_key
                            .iter()
                            .map(|key| HetznerSshKeyLookup::new(&key.name))
                            .collect(),
                    )
                    .with_volumes(volumes)
                    .with_labels(declared);
            resource.user_data = UserData::Hash(user_data);
            resource.ipv4 = ipv4;
            let diff = provider.servers().diff(&resource, &context).await?;
            let detail = format!(
                "{} in {}{}",
                server.server_type,
                server.location,
                server
                    .public_ipv4
                    .as_ref()
                    .map(|ip| format!(", {ip}"))
                    .unwrap_or_default()
            );
            resource_state(ResourceKind::Server, &server.name, diff, Some(detail))
        }
        None => missing(ResourceKind::Server, name),
    };
    resources.push(server);

    let status = if resources
        .iter()
        .any(|resource| resource.status == DiffStatus::Missing)
    {
        DeploymentStatus::Incomplete
    } else if resources
        .iter()
        .any(|resource| resource.status != DiffStatus::UpToDate)
    {
        DeploymentStatus::Changed
    } else {
        DeploymentStatus::Ok
    };
    Ok(DeploymentState {
        name: name.clone(),
        status,
        resources,
    })
}

fn resource_state(
    kind: ResourceKind,
    name: &str,
    diff: ResourceDiff,
    detail: Option<String>,
) -> ResourceState {
    ResourceState {
        kind,
        name: name.to_string(),
        status: diff.status,
        changes: diff
            .changes
            .iter()
            .map(|change| change.log_text())
            .collect(),
        detail,
    }
}

fn missing(kind: ResourceKind, name: &str) -> ResourceState {
    ResourceState {
        kind,
        name: name.to_string(),
        status: DiffStatus::Missing,
        changes: Vec::new(),
        detail: None,
    }
}

/// the cloud-init a deployment's server is created with, until the install of sak is there
pub const USER_DATA: &str = "#cloud-config\npackage_update: true\n";

/// a sak name: 1 to 63 lower case letters, digits and dashes, a letter or digit at both
/// ends, what hetzner takes as a name and dns as a label
pub fn check_name(name: &str) -> Result<()> {
    let valid = !name.is_empty()
        && name.len() <= 63
        && name
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
        && !name.starts_with('-')
        && !name.ends_with('-');
    if valid {
        Ok(())
    } else {
        Err(Error::message(format!(
            "'{name}' is no sak name: 1 to 63 lower case letters, digits and dashes, starting and ending with a letter or digit"
        )))
    }
}

/// what a deployment consists of, as the wizard creates it: an ssh key, a data volume, a
/// primary ipv4 and ipv6, a firewall and the server made of them, every one labeled with
/// the sak name
#[derive(Clone, Debug)]
pub struct DeploymentSpec {
    pub name: String,
    /// the public key the server is created with, `ssh-ed25519 ...`
    pub public_key: String,
    pub location: Location,
    pub server_type: ServerType,
    pub image: String,
    pub data_volume_gb: u64,
    pub user_data: String,
}

impl DeploymentSpec {
    pub fn new(
        name: &str,
        public_key: &str,
        location: Location,
        server_type: ServerType,
    ) -> Result<Self> {
        check_name(name)?;
        Ok(DeploymentSpec {
            name: name.to_string(),
            public_key: public_key.to_string(),
            location,
            server_type,
            image: "debian-13".to_string(),
            data_volume_gb: 16,
            user_data: USER_DATA.to_string(),
        })
    }

    /// the name label every resource carries
    pub fn labels(&self) -> Labels {
        Labels::from([(NAME_LABEL.to_string(), self.name.clone())])
    }

    pub fn ssh_key(&self) -> HetznerSshKey {
        HetznerSshKey::new(&self.name, &self.public_key).with_labels(self.labels())
    }

    pub fn data_volume(&self) -> HetznerVolume {
        HetznerVolume::new(
            &format!("{}-data", self.name),
            self.location,
            self.data_volume_gb,
        )
        .with_labels(self.labels())
    }

    pub fn ipv4(&self) -> HetznerPrimaryIp {
        HetznerPrimaryIp::ipv4(&format!("{}-ipv4", self.name), self.location)
            .with_labels(self.labels())
    }

    pub fn ipv6(&self) -> HetznerPrimaryIp {
        HetznerPrimaryIp::ipv6(&format!("{}-ipv6", self.name), self.location)
            .with_labels(self.labels())
    }

    /// ssh, https and icmp from anywhere, applied to the servers of the deployment
    pub fn firewall(&self) -> HetznerFirewall {
        HetznerFirewall::new(
            &self.name,
            vec![inbound_tcp("22"), inbound_tcp("443"), inbound_icmp()],
        )
        .applied_to(self.labels())
        .with_labels(self.labels())
    }

    pub fn server(&self) -> HetznerServer {
        HetznerServer::new(&self.name, self.location, self.server_type, &self.user_data)
            .with_image(&self.image)
            .with_ssh_keys(vec![self.ssh_key().as_lookup()])
            .with_volumes(vec![self.data_volume().as_lookup()])
            .with_ipv4(self.ipv4().as_lookup())
            .with_ipv6(self.ipv6().as_lookup())
            .with_labels(self.labels())
    }

    /// the resource group in a registry of the provider, ready to diff and apply
    pub fn registry(&self, provider: &HetznerProviderRuntime) -> Result<Registry> {
        provider
            .registry()
            .with(self.ssh_key())?
            .with(self.data_volume())?
            .with(self.ipv4())?
            .with(self.ipv6())?
            .with(self.firewall())?
            .with(self.server())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hetzner::api::{Location, ServerType};
    use crate::hetzner::labels::sha256_hex;
    use crate::hetzner::test_support::mock_api_all;
    use crate::hetzner::{SSH_KEYS_LABEL, USER_DATA_LABEL};
    use serde_json::{Value, json};
    use std::sync::Arc;

    const FINGERPRINT: &str = "99:fc:9b:f4:04:69:2c:9d:30:d5:2c:d9:1e:ca:b2:76";

    fn named(name: &str) -> Value {
        json!({ NAME_LABEL: name })
    }

    fn key(id: u64, name: &str, labels: Value) -> Value {
        json!({ "id": id, "name": name, "fingerprint": FINGERPRINT, "public_key": "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE+u0dEVRZDzzp4E1teCqF49r8ig3YEk8eaPqNWfDcPb pelle@fry", "labels": labels })
    }

    fn firewall(id: u64, name: &str, labels: Value) -> Value {
        json!({ "id": id, "name": name, "rules": [{ "direction": "in", "protocol": "icmp", "source_ips": ["0.0.0.0/0"] }], "applied_to": [], "labels": labels })
    }

    /// a server created with the key of the tests and the user data `#cloud-config\n`
    fn server(id: u64, name: &str, mut labels: Value) -> Value {
        let mut hetzner = HetznerLabels::new();
        hetzner.add_hashed(SSH_KEYS_LABEL, FINGERPRINT).unwrap();
        hetzner
            .add_hashed(USER_DATA_LABEL, "#cloud-config\n")
            .unwrap();
        for (key, value) in hetzner.raw() {
            labels[key] = json!(value);
        }
        json!({
            "id": id, "name": name, "status": "running", "server_type": { "id": 1, "name": "cx23" },
            "image": { "id": 2, "name": "debian-13" }, "location": { "id": 3, "name": "hel1" },
            "protection": { "delete": false, "rebuild": false }, "volumes": [],
            "public_net": { "ipv4": { "id": null, "ip": "1.2.3.4" }, "ipv6": null }, "private_net": [], "labels": labels
        })
    }

    async fn project() -> (wiremock::MockServer, Arc<HetznerApi>) {
        mock_api_all(&[
            (
                "servers",
                vec![
                    server(1, "alpha", named("alpha")),
                    server(2, "other", json!({})),
                ],
            ),
            (
                "ssh_keys",
                vec![
                    key(7, "alpha", named("alpha")),
                    key(8, "beta", named("beta")),
                ],
            ),
            ("firewalls", vec![firewall(5, "alpha", named("alpha"))]),
            ("volumes", vec![]),
            ("primary_ips", vec![]),
        ])
        .await
    }

    #[tokio::test]
    async fn discover_groups_the_labeled_resources_by_name() {
        let (_server, api) = project().await;

        let deployments = discover(&api).await.unwrap();

        let names: Vec<&str> = deployments
            .iter()
            .map(|deployment| deployment.name.as_str())
            .collect();
        assert_eq!(names, vec!["alpha", "beta"]);
        assert!(
            deployments[0].server.is_some()
                && deployments[0].ssh_key.is_some()
                && deployments[0].firewall.is_some()
        );
        assert!(
            deployments[1].server.is_none()
                && deployments[1].ssh_key.is_some()
                && deployments[1].firewall.is_none()
        );
    }

    #[tokio::test]
    async fn the_state_is_ok_for_a_complete_deployment_and_incomplete_otherwise() {
        let (_server, api) = project().await;
        let provider =
            HetznerProviderRuntime::from_api(api.clone(), Location::hel1, ServerType::cx23);
        let deployments = discover(&api).await.unwrap();

        let alpha = state(&provider, &deployments[0]).await.unwrap();
        assert_eq!(alpha.status, DeploymentStatus::Ok, "{:?}", alpha.resources);
        assert_eq!(
            alpha.resources[2].detail.as_deref(),
            Some("cx23 in hel1, 1.2.3.4")
        );
        assert_eq!(alpha.resources[0].detail.as_deref(), Some(FINGERPRINT));

        let beta = state(&provider, &deployments[1]).await.unwrap();
        assert_eq!(beta.status, DeploymentStatus::Incomplete);
        let statuses: Vec<(ResourceKind, DiffStatus)> = beta
            .resources
            .iter()
            .map(|resource| (resource.kind, resource.status))
            .collect();
        assert_eq!(
            statuses,
            vec![
                (ResourceKind::SshKey, DiffStatus::UpToDate),
                (ResourceKind::Firewall, DiffStatus::Missing),
                (ResourceKind::Server, DiffStatus::Missing)
            ]
        );
    }

    #[tokio::test]
    async fn a_server_created_with_another_key_is_a_change() {
        let mut other_key = key(7, "alpha", named("alpha"));
        other_key["fingerprint"] = json!("aa:bb");
        let (_server, api) = mock_api_all(&[
            ("servers", vec![server(1, "alpha", named("alpha"))]),
            ("ssh_keys", vec![other_key]),
            ("firewalls", vec![firewall(5, "alpha", named("alpha"))]),
            ("volumes", vec![]),
            ("primary_ips", vec![]),
        ])
        .await;
        let provider =
            HetznerProviderRuntime::from_api(api.clone(), Location::hel1, ServerType::cx23);
        let deployments = discover(&api).await.unwrap();

        let alpha = state(&provider, &deployments[0]).await.unwrap();

        assert_eq!(alpha.status, DeploymentStatus::Changed);
        assert_eq!(alpha.resources[2].changes.len(), 1);
        assert!(
            alpha.resources[2].changes[0].starts_with("ssh keys should be"),
            "{}",
            alpha.resources[2].changes[0]
        );
        assert_eq!(
            serde_json::to_value(alpha.status).unwrap(),
            json!("changed")
        );
        let _ = sha256_hex;
    }

    #[test]
    fn a_sak_name_is_a_lower_case_hostname_label() {
        assert!(check_name("alpha-1").is_ok());
        for name in [
            "",
            "Alpha",
            "-alpha",
            "alpha-",
            "al pha",
            "a_b",
            &"a".repeat(64),
        ] {
            assert!(check_name(name).is_err(), "{name}");
        }
    }

    #[tokio::test]
    async fn the_spec_plans_six_labeled_resources_in_dependency_order() {
        let (_server, api) = mock_api_all(&[
            ("servers", vec![]),
            ("ssh_keys", vec![]),
            ("firewalls", vec![]),
            ("volumes", vec![]),
            ("primary_ips", vec![]),
        ])
        .await;
        let provider =
            HetznerProviderRuntime::from_api(api.clone(), Location::hel1, ServerType::cx23);
        let spec = DeploymentSpec::new(
            "alpha",
            "ssh-ed25519 AAAA",
            Location::hel1,
            ServerType::cx23,
        )
        .unwrap();
        assert_eq!(spec.server().labels[NAME_LABEL], "alpha");
        assert_eq!(spec.data_volume().name, "alpha-data");

        let diffs = spec.registry(&provider).unwrap().diff_all().await.unwrap();

        let planned: Vec<(String, DiffStatus)> = diffs
            .iter()
            .map(|diff| (diff.resource.log_text.clone(), diff.status))
            .collect();
        assert_eq!(
            planned,
            vec![
                ("ssh key 'alpha'".to_string(), DiffStatus::Missing),
                ("volume 'alpha-data'".to_string(), DiffStatus::Missing),
                ("primary ip 'alpha-ipv4'".to_string(), DiffStatus::Missing),
                ("primary ip 'alpha-ipv6'".to_string(), DiffStatus::Missing),
                ("firewall 'alpha'".to_string(), DiffStatus::Missing),
                ("server 'alpha'".to_string(), DiffStatus::ParentMissing),
            ]
        );
    }
}
