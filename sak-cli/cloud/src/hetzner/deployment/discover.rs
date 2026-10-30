//! the deployments of a project, found by the name label on their resources

use crate::api::{Labels, Result};
use crate::hetzner::api::{HetznerApi, ListQuery, Location};
use crate::hetzner::deployment::{DnsMode, frontend_host};
use crate::hetzner::firewall::HetznerFirewallRuntime;
use crate::hetzner::primary_ip::HetznerPrimaryIpRuntime;
use crate::hetzner::server::HetznerServerRuntime;
use crate::hetzner::ssh_key::HetznerSshKeyRuntime;
use crate::hetzner::volume::HetznerVolumeRuntime;
use crate::hetzner::{
    ACME_CA_LABEL, DNS_MODE_LABEL, NAME_LABEL, ROOT_DOMAIN_LABEL, USER_MODE_LABEL, VERSION_LABEL,
};
use std::collections::BTreeMap;
use tracing::warn;

/// the resources of a deployment in the cloud: the resources with its name label
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
    pub(super) fn new(name: &str) -> Self {
        DiscoveredDeployment {
            name: name.to_string(),
            ..Default::default()
        }
    }

    /// the dns mode on its resources, the label of the server first; none when no resource has
    /// it (created before the label) or the value is unknown
    pub fn dns_mode(&self) -> Option<DnsMode> {
        self.label(DNS_MODE_LABEL)
            .and_then(|value| DnsMode::parse(value))
    }

    /// the root domain on its resources, the label of the server first; none when no resource
    /// has it
    pub fn root_domain(&self) -> Option<String> {
        self.label(ROOT_DOMAIN_LABEL).cloned()
    }

    /// the user mode on its resources, `single` or `multi`; none for a deployment created before
    /// the label
    pub fn user_mode(&self) -> Option<String> {
        self.label(USER_MODE_LABEL).cloned()
    }

    /// the acme ca on its resources, `staging`; none for the default
    pub fn acme_ca(&self) -> Option<String> {
        self.label(ACME_CA_LABEL).cloned()
    }

    /// the version of sak that last created or deployed it, as its resources tell
    pub fn version(&self) -> Option<String> {
        self.label(VERSION_LABEL).cloned()
    }

    /// the url of its frontend, `https://sak.<base domain>`; none without a root domain on its
    /// resources. Without a dns mode it uses `subdomain`
    pub fn frontend_url(&self) -> Option<String> {
        let root_domain = self.root_domain()?;
        let dns_mode = self.dns_mode().unwrap_or_default();
        Some(format!(
            "https://{}",
            frontend_host(&self.name, dns_mode, &root_domain)
        ))
    }

    /// the location of its server, else of its volumes, else of its primary ips; none without
    /// them. Resources in different locations are an error that names them: a deploy must not
    /// guess
    pub fn location(&self) -> std::result::Result<Option<Location>, String> {
        let mut found: Vec<(String, Location)> = Vec::new();
        found.extend(
            self.server
                .iter()
                .map(|server| (format!("server '{}'", server.name), server.location)),
        );
        found.extend(
            self.volumes
                .iter()
                .map(|volume| (format!("volume '{}'", volume.name), volume.location)),
        );
        found.extend(
            self.primary_ips
                .iter()
                .map(|ip| (format!("primary ip '{}'", ip.name), ip.location)),
        );
        let Some((_, location)) = found.first() else {
            return Ok(None);
        };
        if found.iter().any(|(_, other)| other != location) {
            let all: Vec<String> = found
                .iter()
                .map(|(resource, location)| format!("{resource} in {location}"))
                .collect();
            return Err(all.join(", "));
        }
        Ok(Some(*location))
    }

    /// the labels of each of its resources: the server, the ssh key, the firewall, the primary
    /// ips, the volumes
    pub fn labels(&self) -> Vec<&Labels> {
        let mut labels: Vec<&Labels> = Vec::new();
        labels.extend(self.server.iter().map(|server| &server.labels));
        labels.extend(self.ssh_key.iter().map(|key| &key.labels));
        labels.extend(self.firewall.iter().map(|firewall| &firewall.labels));
        labels.extend(self.primary_ips.iter().map(|ip| &ip.labels));
        labels.extend(self.volumes.iter().map(|volume| &volume.labels));
        labels
    }

    /// the value of `key` on the first of its resources that has it, in the order of `labels`
    pub(super) fn label(&self, key: &str) -> Option<&String> {
        self.labels().into_iter().find_map(|labels| labels.get(key))
    }
}

/// the deployments of the project, sorted by name. A deployment has one server, ssh key and
/// firewall; a second one with the same name label is left out with a warning
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

/// the deployment of the labels, added when it is not there yet; none without the name label
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hetzner::api::{Location, ServerType};
    use crate::hetzner::deployment::fixtures::*;
    use crate::hetzner::deployment::*;
    use crate::hetzner::test_support::mock_api_all;
    use crate::providers::hetzner::HetznerProviderRuntime;
    use serde_json::json;

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
    async fn a_server_of_an_unknown_type_location_and_status_is_listed_and_not_recreated() {
        // what Hetzner may answer after this release: a new type, location and status
        let mut alpha = server(1, "sak-alpha", named("alpha"));
        alpha["server_type"]["name"] = json!("cx99");
        alpha["location"]["name"] = json!("xyz1");
        alpha["status"] = json!("frozen");
        let (_server, api) = mock_api_all(&[
            ("servers", vec![alpha]),
            ("ssh_keys", vec![key(7, "sak-alpha", named("alpha"))]),
            ("firewalls", vec![]),
            ("volumes", vec![]),
            ("primary_ips", vec![]),
        ])
        .await;
        let deployments = discover(&api).await.unwrap();
        let server = deployments[0].server.as_ref().unwrap();
        assert_eq!(server.server_type, ServerType::unknown);
        assert_eq!(server.location, Location::unknown);

        let provider =
            HetznerProviderRuntime::from_api(api.clone(), Location::nbg1, ServerType::cx23);
        let alpha = state(&provider, &deployments[0]).await;
        assert_eq!(alpha.error, None);
        let changes: Vec<&String> = alpha
            .resources
            .iter()
            .filter(|resource| resource.kind == ResourceKind::Server)
            .flat_map(|resource| &resource.changes)
            .collect();
        assert!(
            changes
                .iter()
                .all(|change| !change.starts_with("type") && !change.starts_with("location")),
            "{changes:?}"
        );
    }

    #[test]
    fn the_location_of_a_deployment_is_its_servers_else_its_volumes_else_its_addresses() {
        let volume: crate::hetzner::api::volumes::Volume = serde_json::from_value(json!({
            "id": 3, "name": "sak-alpha-data", "format": "ext4", "linux_device": "/dev/x", "size": 16,
            "protection": { "delete": true }, "server": null, "status": "available",
            "location": { "id": 2, "name": "nbg1" }, "labels": {}
        }))
        .unwrap();
        let ip = |location: &str| -> crate::hetzner::api::primary_ips::PrimaryIp {
            serde_json::from_value(json!({
                "id": 11, "name": "sak-alpha-ipv4", "ip": "1.2.3.4", "type": "ipv4", "assignee_id": null,
                "assignee_type": "unassigned", "auto_delete": false, "protection": { "delete": true },
                "location": { "id": 3, "name": location }, "labels": {}
            }))
            .unwrap()
        };
        let mut deployment = DiscoveredDeployment::new("alpha");
        assert_eq!(deployment.location(), Ok(None));
        deployment.primary_ips = vec![ip("nbg1").into()];
        assert_eq!(deployment.location(), Ok(Some(Location::nbg1)));
        // without a server, the volume gives the location
        deployment.volumes = vec![volume.into()];
        assert_eq!(deployment.location(), Ok(Some(Location::nbg1)));
        // resources in two locations: an error, no guess
        deployment.primary_ips = vec![ip("hel1").into()];
        assert_eq!(
            deployment.location(),
            Err("volume 'sak-alpha-data' in nbg1, primary ip 'sak-alpha-ipv4' in hel1".to_string())
        );
    }
}
