//! the deployments of a project, found by the name label on their resources; their dns records
//! tell the root domain and the dns mode

use crate::api::{Labels, Result};
use crate::hetzner::api::{HetznerApi, ListQuery, Location};
use crate::hetzner::deployment::ResourceKind;
use crate::hetzner::deployment::spec::{IPV4_SUFFIX, IPV6_SUFFIX};
use crate::hetzner::deployment::{DATA_VOLUME_SUFFIX, DnsMode, RESOURCE_PREFIX, frontend_host};
use crate::hetzner::firewall::HetznerFirewallRuntime;
use crate::hetzner::primary_ip::HetznerPrimaryIpRuntime;
use crate::hetzner::server::HetznerServerRuntime;
use crate::hetzner::ssh_key::HetznerSshKeyRuntime;
use crate::hetzner::volume::HetznerVolumeRuntime;
use crate::hetzner::{NAME_LABEL, VERSION_LABEL};
use std::collections::BTreeMap;

/// the resources of a deployment in the cloud: the resources with its name label
#[derive(Clone, Debug, Default)]
pub struct DiscoveredDeployment {
    pub name: String,
    pub ssh_key: Option<HetznerSshKeyRuntime>,
    pub firewall: Option<HetznerFirewallRuntime>,
    pub server: Option<HetznerServerRuntime>,
    /// the volume of its declared name, `sak-<name>-data`
    pub data_volume: Option<HetznerVolumeRuntime>,
    /// the primary ip of its declared name, `sak-<name>-ipv4`
    pub ipv4: Option<HetznerPrimaryIpRuntime>,
    /// the primary ip of its declared name, `sak-<name>-ipv6`
    pub ipv6: Option<HetznerPrimaryIpRuntime>,
    /// the other resources with its name label: a second server, ssh key or firewall, a volume or
    /// primary ip of another name. Kept so the list shows them and the password is checked against
    /// their hashes too
    pub left_out: Vec<LeftOut>,
    /// the root domain whose zone has its dns records and the dns mode their names tell; none
    /// without records
    pub domain: Option<(String, DnsMode)>,
}

/// see `DiscoveredDeployment::left_out`
#[derive(Clone, Debug)]
pub struct LeftOut {
    pub kind: ResourceKind,
    pub name: String,
    pub labels: Labels,
}

impl DiscoveredDeployment {
    pub(super) fn new(name: &str) -> Self {
        DiscoveredDeployment {
            name: name.to_string(),
            ..Default::default()
        }
    }

    /// the dns mode its dns records tell; none without records
    pub fn dns_mode(&self) -> Option<DnsMode> {
        self.domain.as_ref().map(|(_, mode)| *mode)
    }

    /// the root domain whose zone has its dns records; none without records
    pub fn root_domain(&self) -> Option<String> {
        self.domain
            .as_ref()
            .map(|(root_domain, _)| root_domain.clone())
    }

    /// the version of sak that last created or deployed it, as its resources tell
    pub fn version(&self) -> Option<String> {
        self.label(VERSION_LABEL).cloned()
    }

    /// the url of its frontend, `https://sak.<base domain>`; none without dns records
    pub fn frontend_url(&self) -> Option<String> {
        let root_domain = self.root_domain()?;
        let dns_mode = self.dns_mode().unwrap_or_default();
        Some(format!(
            "https://{}",
            frontend_host(&self.name, dns_mode, &root_domain)
        ))
    }

    /// the location of its server, else of its volume, else of its primary ips. Resources in
    /// different locations are an error that names them: a deploy must not guess
    pub fn location(&self) -> std::result::Result<Option<Location>, String> {
        let found = self.locations();
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

    /// the location of each of its server, volume and primary ips, in this order
    pub(super) fn locations(&self) -> Vec<(String, Location)> {
        let mut found: Vec<(String, Location)> = Vec::new();
        found.extend(
            self.server
                .iter()
                .map(|server| (format!("server '{}'", server.name), server.location)),
        );
        found.extend(
            self.data_volume
                .iter()
                .map(|volume| (format!("volume '{}'", volume.name), volume.location)),
        );
        found.extend(
            self.primary_ips()
                .map(|ip| (format!("primary ip '{}'", ip.name), ip.location)),
        );
        found
    }

    /// its primary ips, ipv4 first
    pub(super) fn primary_ips(&self) -> impl Iterator<Item = &HetznerPrimaryIpRuntime> {
        self.ipv4.iter().chain(self.ipv6.iter())
    }

    /// the labels of each of its resources, in this order: server, ssh key, firewall, primary ips,
    /// volume, the ones left out
    pub fn labels(&self) -> Vec<&Labels> {
        let mut labels: Vec<&Labels> = Vec::new();
        labels.extend(self.server.iter().map(|server| &server.labels));
        labels.extend(self.ssh_key.iter().map(|key| &key.labels));
        labels.extend(self.firewall.iter().map(|firewall| &firewall.labels));
        labels.extend(self.primary_ips().map(|ip| &ip.labels));
        labels.extend(self.data_volume.iter().map(|volume| &volume.labels));
        labels.extend(self.left_out.iter().map(|left_out| &left_out.labels));
        labels
    }

    /// the value on the first resource that has it, in the order of `labels`
    pub(super) fn label(&self, key: &str) -> Option<&String> {
        self.labels().into_iter().find_map(|labels| labels.get(key))
    }
}

/// sorted by name. A deployment has the first server, ssh key and firewall found and the volume
/// and primary ips of their declared names; any other resource with its name label is `left_out`
pub async fn discover(api: &HetznerApi) -> Result<Vec<DiscoveredDeployment>> {
    let query = ListQuery::new().label_exists(NAME_LABEL);
    let mut deployments: BTreeMap<String, DiscoveredDeployment> = BTreeMap::new();

    for server in api.servers().list(&query).await? {
        let Some(deployment) = of(&mut deployments, &server.labels) else {
            continue;
        };
        if deployment.server.is_some() {
            deployment.left_out.push(LeftOut {
                kind: ResourceKind::Server,
                name: server.name.clone(),
                labels: server.labels.clone(),
            });
            continue;
        }
        deployment.server = Some(server.into());
    }
    for key in api.ssh_keys().list(&query).await? {
        let Some(deployment) = of(&mut deployments, &key.labels) else {
            continue;
        };
        if deployment.ssh_key.is_some() {
            deployment.left_out.push(LeftOut {
                kind: ResourceKind::SshKey,
                name: key.name.clone(),
                labels: key.labels.clone(),
            });
            continue;
        }
        deployment.ssh_key = Some(key.into());
    }
    for firewall in api.firewalls().list(&query).await? {
        let Some(deployment) = of(&mut deployments, &firewall.labels) else {
            continue;
        };
        if deployment.firewall.is_some() {
            deployment.left_out.push(LeftOut {
                kind: ResourceKind::Firewall,
                name: firewall.name.clone(),
                labels: firewall.labels.clone(),
            });
            continue;
        }
        deployment.firewall = Some(HetznerFirewallRuntime::from_firewall(firewall));
    }
    for volume in api.volumes().list(&query).await? {
        let Some(deployment) = of(&mut deployments, &volume.labels) else {
            continue;
        };
        if volume.name != declared(&deployment.name, DATA_VOLUME_SUFFIX) {
            deployment.left_out.push(LeftOut {
                kind: ResourceKind::Volume,
                name: volume.name.clone(),
                labels: volume.labels.clone(),
            });
            continue;
        }
        deployment.data_volume = Some(volume.into());
    }
    for ip in api.primary_ips().list(&query).await? {
        let Some(deployment) = of(&mut deployments, &ip.labels) else {
            continue;
        };
        if ip.name == declared(&deployment.name, IPV4_SUFFIX) {
            deployment.ipv4 = Some(ip.into());
        } else if ip.name == declared(&deployment.name, IPV6_SUFFIX) {
            deployment.ipv6 = Some(ip.into());
        } else {
            deployment.left_out.push(LeftOut {
                kind: ResourceKind::PrimaryIp,
                name: ip.name.clone(),
                labels: ip.labels.clone(),
            });
        }
    }
    // the root domain and the dns mode: the zone with the records of the deployment
    let zones = api.zones();
    for zone in zones.list(&ListQuery::new()).await? {
        for rrset in zones.rrsets(&zone.name).list(&query).await? {
            let Some(deployment) = rrset
                .labels
                .get(NAME_LABEL)
                .and_then(|name| deployments.get_mut(name))
            else {
                continue;
            };
            if let Some(mode) = DnsMode::of_record(&rrset.name, &deployment.name) {
                deployment.domain.get_or_insert((zone.name.clone(), mode));
            }
        }
    }
    Ok(deployments.into_values().collect())
}

/// `sak-<name><suffix>`; names are unique per kind in a project, so one resource has it at most
fn declared(name: &str, suffix: &str) -> String {
    format!("{RESOURCE_PREFIX}{name}{suffix}")
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

    #[tokio::test]
    async fn a_second_server_is_left_out_listed_and_its_labels_count() {
        let mut second = named("alpha");
        second["sak.pelle.io/password-hash"] = json!("another");
        let (_server, api) = mock_api_all(&[
            (
                "servers",
                vec![
                    server(1, "sak-alpha", named("alpha")),
                    server(2, "sak-alpha-2", second),
                ],
            ),
            ("ssh_keys", vec![key(7, "sak-alpha", named("alpha"))]),
            ("firewalls", vec![]),
            ("volumes", vec![]),
            ("primary_ips", vec![]),
        ])
        .await;

        let deployments = discover(&api).await.unwrap();
        let alpha = &deployments[0];
        assert_eq!(alpha.server.as_ref().unwrap().name, "sak-alpha");
        assert_eq!(alpha.left_out.len(), 1);
        assert_eq!(alpha.left_out[0].kind, ResourceKind::Server);
        assert_eq!(alpha.left_out[0].name, "sak-alpha-2");
        // its hash is among the labels a password is checked against
        assert!(alpha.labels().iter().any(|labels| {
            labels.get("sak.pelle.io/password-hash") == Some(&"another".to_string())
        }));

        let provider = HetznerProviderRuntime::from_api(api.clone());
        let listed = state(&provider, alpha).await;
        assert!(
            listed
                .resources
                .iter()
                .any(|resource| resource.name == "sak-alpha-2"
                    && resource.status == Some(crate::api::DiffStatus::Duplicate))
        );
        assert_ne!(listed.status, DeploymentStatus::Ok);
    }
    use crate::providers::hetzner::HetznerProviderRuntime;
    use serde_json::json;

    #[tokio::test]
    async fn the_records_of_a_deployment_tell_its_root_domain_and_dns_mode() {
        use crate::hetzner::dns_zone::provisioner_tests::{rrset, zone};
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, ResponseTemplate};
        let alpha = json!({ NAME_LABEL: "alpha" });
        let beta = json!({ NAME_LABEL: "beta" });
        let (server, api) = mock_api_all(&[
            ("servers", vec![]),
            (
                "ssh_keys",
                vec![
                    key(7, "sak-alpha", alpha.clone()),
                    key(8, "sak-beta", beta.clone()),
                ],
            ),
            ("firewalls", vec![]),
            ("volumes", vec![]),
            ("primary_ips", vec![]),
            ("zones", vec![zone(1, "pelle.io", json!({}))]),
        ])
        .await;
        Mock::given(method("GET"))
            .and(path("/zones/pelle.io/rrsets"))
            .respond_with(ResponseTemplate::new(200).set_body_json(
                crate::hetzner::test_support::page(
                    "rrsets",
                    vec![
                        rrset(1, "@", alpha.clone()),
                        rrset(1, "*", alpha),
                        rrset(1, "www", beta),
                    ],
                ),
            ))
            .mount(&server)
            .await;

        let deployments = discover(&api).await.unwrap();

        assert_eq!(deployments[0].root_domain().as_deref(), Some("pelle.io"));
        assert_eq!(deployments[0].dns_mode(), Some(DnsMode::Domain));
        assert_eq!(
            deployments[0].frontend_url().as_deref(),
            Some("https://sak.pelle.io")
        );
        // www is no name of the deployment beta: no root domain for it
        assert_eq!(deployments[1].root_domain(), None);
    }

    #[tokio::test]
    async fn a_volume_and_a_primary_ip_of_another_name_are_left_out_and_listed() {
        let labels = json!({ NAME_LABEL: "alpha" });
        let mut old = volume(4, "sak-alpha-old", "hel1", labels.clone());
        old["size"] = json!(64);
        let mut data = volume(3, "sak-alpha-data", "hel1", labels.clone());
        data["size"] = json!(32);
        let (_server, api) = mock_api_all(&[
            ("servers", vec![]),
            ("ssh_keys", vec![key(7, "sak-alpha", labels.clone())]),
            ("firewalls", vec![]),
            // listed first, the volume of another name must not set the declared size
            ("volumes", vec![old, data]),
            (
                "primary_ips",
                vec![
                    ip(10, "sak-alpha-old", "ipv4", "hel1", labels.clone()),
                    ip(11, "sak-alpha-ipv4", "ipv4", "hel1", labels.clone()),
                ],
            ),
        ])
        .await;

        let deployments = discover(&api).await.unwrap();
        let alpha = &deployments[0];
        assert_eq!(alpha.data_volume.as_ref().unwrap().name, "sak-alpha-data");
        assert_eq!(alpha.ipv4.as_ref().unwrap().name, "sak-alpha-ipv4");
        assert!(alpha.ipv6.is_none());
        let left_out: Vec<(ResourceKind, &str)> = alpha
            .left_out
            .iter()
            .map(|left_out| (left_out.kind, left_out.name.as_str()))
            .collect();
        assert_eq!(
            left_out,
            vec![
                (ResourceKind::Volume, "sak-alpha-old"),
                (ResourceKind::PrimaryIp, "sak-alpha-old"),
            ]
        );

        let provider = HetznerProviderRuntime::from_api(api.clone());
        let listed = state(&provider, alpha).await;
        assert_eq!(listed.error, None);
        let statuses: Vec<(ResourceKind, &str, Option<crate::api::DiffStatus>)> = listed
            .resources
            .iter()
            .filter(|resource| {
                matches!(
                    resource.kind,
                    ResourceKind::Volume | ResourceKind::PrimaryIp
                )
            })
            .map(|resource| (resource.kind, resource.name.as_str(), resource.status))
            .collect();
        use crate::api::DiffStatus::{Duplicate, Missing, UpToDate};
        assert_eq!(
            statuses,
            vec![
                (ResourceKind::Volume, "sak-alpha-data", Some(UpToDate)),
                (ResourceKind::PrimaryIp, "sak-alpha-ipv4", Some(UpToDate)),
                (ResourceKind::PrimaryIp, "sak-alpha-ipv6", Some(Missing)),
                (ResourceKind::Volume, "sak-alpha-old", Some(Duplicate)),
                (ResourceKind::PrimaryIp, "sak-alpha-old", Some(Duplicate)),
            ]
        );
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

        let provider = HetznerProviderRuntime::from_api(api.clone());
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
        deployment.ipv4 = Some(ip("nbg1").into());
        assert_eq!(deployment.location(), Ok(Some(Location::nbg1)));
        // without a server, the volume gives the location
        deployment.data_volume = Some(volume.into());
        assert_eq!(deployment.location(), Ok(Some(Location::nbg1)));
        // resources in two locations: an error, no guess
        deployment.ipv4 = Some(ip("hel1").into());
        assert_eq!(
            deployment.location(),
            Err("volume 'sak-alpha-data' in nbg1, primary ip 'sak-alpha-ipv4' in hel1".to_string())
        );
    }
}
