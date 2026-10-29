//! the sak deployments of a cloud project: everything carrying the name label, grouped by
//! its value, and the state of each against the resource group a deployment consists of

use crate::api::{DiffStatus, Error, Labels, Lookup, Registry, Resource, ResourceDiff, Result};
use crate::hetzner::api::zones::RRType;
use crate::hetzner::api::{HetznerApi, ListQuery, Location, ServerType};
use crate::hetzner::dns_record::{HetznerDnsRecord, HetznerDnsRecordLookup, RecordValue};
use crate::hetzner::dns_zone::{HetznerDnsZoneListing, HetznerDnsZoneLookup};
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
use crate::hetzner::{
    DNS_MODE_LABEL, NAME_LABEL, PASSWORD_HASH_LABEL, ROOT_DOMAIN_LABEL, USER_DATA_LABEL,
};
use crate::providers::hetzner::HetznerProviderRuntime;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fmt;
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

    /// the dns mode on its resources, the server's first; none when no resource carries one
    /// (created before the dns mode was kept) or the value is unknown
    pub fn dns_mode(&self) -> Option<DnsMode> {
        self.label(DNS_MODE_LABEL)
            .and_then(|value| DnsMode::parse(value))
    }

    /// the root domain on its resources, the server's first; none when no resource carries one
    pub fn root_domain(&self) -> Option<String> {
        self.label(ROOT_DOMAIN_LABEL).cloned()
    }

    /// where its frontend is, `https://sak.<base domain>`; none without a root domain on its
    /// resources, a deployment without a dns mode is a subdomain one
    pub fn frontend_url(&self) -> Option<String> {
        let root_domain = self.root_domain()?;
        let dns_mode = self.dns_mode().unwrap_or_default();
        Some(format!(
            "https://{}",
            frontend_host(&self.name, dns_mode, &root_domain)
        ))
    }

    /// where it is: the location of its server, else of its volumes, else of its primary ips;
    /// none when it has none of them. Resources in different locations are an error that names
    /// them, a deploy must not guess
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

    /// the value of `key` on the first of its resources that carries it
    fn label(&self, key: &str) -> Option<&String> {
        let mut labels: Vec<&Labels> = Vec::new();
        labels.extend(self.server.iter().map(|server| &server.labels));
        labels.extend(self.ssh_key.iter().map(|key| &key.labels));
        labels.extend(self.firewall.iter().map(|firewall| &firewall.labels));
        labels.extend(self.primary_ips.iter().map(|ip| &ip.labels));
        labels.extend(self.volumes.iter().map(|volume| &volume.labels));
        labels.into_iter().find_map(|labels| labels.get(key))
    }
}

/// a dns zone of the project, a root domain a deployment can use, with the deployments
/// that use it already: every entry (the zone or one of its rrsets) labeled with a dns mode
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RootDomain {
    /// `pelle.io`
    pub name: String,
    pub uses: Vec<DomainUse>,
}

/// a deployment found on the entries of a root domain
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct DomainUse {
    /// the name on the entry, none for an entry with a dns mode but no name
    pub name: Option<String>,
    pub dns_mode: DnsMode,
}

/// why a deployment cannot use a root domain in a dns mode
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RootDomainConflict {
    /// domain mode asked, another deployment manages the root domain in domain mode already
    DomainTaken { by: Vec<Option<String>> },
    /// the mode asked is not the one other deployments use the root domain in: subdomain on
    /// the root domain of a domain deployment (`used` domain), domain on one of subdomain
    /// deployments (`used` subdomain)
    MixedModes {
        used: DnsMode,
        by: Vec<Option<String>>,
    },
}

impl RootDomain {
    /// the root domain of a zone listing: the dns modes on the zone and its rrsets, each
    /// deployment once
    pub fn of(listing: &HetznerDnsZoneListing) -> Self {
        let mut uses: Vec<DomainUse> = std::iter::once(&listing.zone.labels)
            .chain(listing.rrsets.iter().map(|rrset| &rrset.labels))
            .filter_map(|labels| {
                let dns_mode = DnsMode::parse(labels.get(DNS_MODE_LABEL)?)?;
                Some(DomainUse {
                    name: labels.get(NAME_LABEL).cloned(),
                    dns_mode,
                })
            })
            .collect();
        uses.sort();
        uses.dedup();
        RootDomain {
            name: listing.zone.name.clone(),
            uses,
        }
    }

    /// the deployments other than `name` using the root domain in `dns_mode`
    fn others(&self, name: &str, dns_mode: DnsMode) -> Vec<Option<String>> {
        self.uses
            .iter()
            .filter(|found| found.dns_mode == dns_mode && found.name.as_deref() != Some(name))
            .map(|found| found.name.clone())
            .collect()
    }

    /// whether the deployment `name` can use the root domain in `dns_mode`: a root domain has
    /// one domain deployment or any number of subdomain deployments; what the deployment
    /// itself left on it does not count, so it can be deployed again and change its mode
    pub fn check(
        &self,
        name: &str,
        dns_mode: DnsMode,
    ) -> std::result::Result<(), RootDomainConflict> {
        let domain = self.others(name, DnsMode::Domain);
        if !domain.is_empty() {
            return Err(match dns_mode {
                DnsMode::Domain => RootDomainConflict::DomainTaken { by: domain },
                DnsMode::Subdomain => RootDomainConflict::MixedModes {
                    used: DnsMode::Domain,
                    by: domain,
                },
            });
        }
        let subdomain = self.others(name, DnsMode::Subdomain);
        if dns_mode == DnsMode::Domain && !subdomain.is_empty() {
            return Err(RootDomainConflict::MixedModes {
                used: DnsMode::Subdomain,
                by: subdomain,
            });
        }
        Ok(())
    }

    /// the dns modes the deployment `name` can use the root domain in, none when another
    /// deployment manages it in domain mode
    pub fn dns_modes(&self, name: &str) -> Vec<DnsMode> {
        DnsMode::ALL
            .into_iter()
            .filter(|mode| self.check(name, *mode).is_ok())
            .collect()
    }
}

/// a dns record of a deployment, found in a zone of the project by the name label
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeploymentRecord {
    pub lookup: HetznerDnsRecordLookup,
    pub labels: Labels,
}

/// the dns records labeled with the name `name`, in the zones of the project
/// (`list_all` of the dns zones): only these are the deployment's to remove
pub fn deployment_records(listings: &[HetznerDnsZoneListing], name: &str) -> Vec<DeploymentRecord> {
    let mut records = Vec::new();
    for listing in listings {
        let zone = HetznerDnsZoneLookup::new(&listing.zone.name);
        for rrset in &listing.rrsets {
            if rrset.labels.get(NAME_LABEL).map(String::as_str) == Some(name) {
                records.push(DeploymentRecord {
                    lookup: HetznerDnsRecordLookup::new(&rrset.name, zone.clone(), rrset.rr_type),
                    labels: rrset.labels.clone(),
                });
            }
        }
    }
    records
}

/// the records in the way of the records of `spec`, as their log texts: every rrset at a name the
/// deployment manages that lacks a label of its identity, whatever its type, so a name another
/// owner uses stops the plan before anything is created. At the zone apex (`@`, domain mode) only
/// those that cannot stand next to the records of the deployment (A, AAAA, CNAME): the others
/// there (SOA, NS, MX, TXT, CAA, …) belong to the zone and stay
pub fn foreign_records(listings: &[HetznerDnsZoneListing], spec: &DeploymentSpec) -> Vec<String> {
    let Some(root_domain) = &spec.root_domain else {
        return Vec::new();
    };
    let names = spec.record_names();
    let identity = spec.identity();
    let owned = |labels: &Labels| {
        identity
            .iter()
            .all(|(key, value)| labels.get(key) == Some(value))
    };
    listings
        .iter()
        .filter(|listing| &listing.zone.name == root_domain)
        .flat_map(|listing| &listing.rrsets)
        .filter(|rrset| names.contains(&rrset.name) && !owned(&rrset.labels))
        .filter(|rrset| {
            rrset.name != "@" || matches!(rrset.rr_type, RRType::A | RRType::AAAA | RRType::CNAME)
        })
        .map(|rrset| {
            HetznerDnsRecordLookup::new(
                &rrset.name,
                HetznerDnsZoneLookup::new(root_domain),
                rrset.rr_type,
            )
            .log_text()
        })
        .collect()
}

/// the records of `found` not among `declared`: all of them on a destroy, those of the old
/// dns mode or root domain on a deploy. Only records that carry every label of `identity` (the
/// name and the password hash) are the deployment's to remove
pub fn stale_records(
    found: &[DeploymentRecord],
    declared: &[HetznerDnsRecord],
    identity: &Labels,
) -> Vec<HetznerDnsRecordLookup> {
    found
        .iter()
        .filter(|record| {
            identity
                .iter()
                .all(|(key, value)| record.labels.get(key) == Some(value))
        })
        .map(|record| record.lookup.clone())
        .filter(|lookup| {
            !declared
                .iter()
                .any(|record| Lookup::name(&record.as_lookup()) == Lookup::name(lookup))
        })
        .collect()
}

/// removes the records, each reported to `on_removed` with whether it was there still
pub async fn remove_records(
    provider: &HetznerProviderRuntime,
    records: &[HetznerDnsRecordLookup],
    on_removed: &mut (dyn FnMut(&HetznerDnsRecordLookup, bool) + Send),
) -> Result<()> {
    for record in records {
        let rrsets = provider.api.zones().rrsets(&record.zone.name);
        let gone = match rrsets.delete(&record.record, record.rr_type).await? {
            Some(action) => {
                rrsets.wait_for_action(&action).await?;
                true
            }
            None => false,
        };
        on_removed(record, gone);
    }
    Ok(())
}

/// the root domains of the project: every dns zone with the deployments using it, by name
pub async fn root_domains(provider: &HetznerProviderRuntime) -> Result<Vec<RootDomain>> {
    Ok(provider
        .dns_zones()
        .list_all()
        .await?
        .iter()
        .map(RootDomain::of)
        .collect())
}

/// the domain the hosts of a deployment are below: the root domain in domain mode,
/// `<name>.<root domain>` in subdomain mode
pub fn base_domain(name: &str, dns_mode: DnsMode, root_domain: &str) -> String {
    match dns_mode {
        DnsMode::Domain => root_domain.to_string(),
        DnsMode::Subdomain => format!("{name}.{root_domain}"),
    }
}

/// the host of the frontend of a deployment, `sak.<base domain>`, as the backend derives it
/// (`SakConfig.frontendHost`); its api is `api.<base domain>`
pub fn frontend_host(name: &str, dns_mode: DnsMode, root_domain: &str) -> String {
    format!("sak.{}", base_domain(name, dns_mode, root_domain))
}

/// how dns is set up for a deployment: `domain` manages `<root domain>` and
/// `*.<root domain>`, `subdomain` (the default) manages `<name>.<root domain>` and
/// `*.<name>.<root domain>`; a root domain has one domain deployment or any number of
/// subdomain deployments (docs/fdr/FDR-001-dns-mode.md)
#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum DnsMode {
    Domain,
    #[default]
    Subdomain,
}

impl DnsMode {
    pub const ALL: [DnsMode; 2] = [DnsMode::Domain, DnsMode::Subdomain];

    /// the value of the label and the command line, `domain` or `subdomain`
    pub fn as_str(self) -> &'static str {
        match self {
            DnsMode::Domain => "domain",
            DnsMode::Subdomain => "subdomain",
        }
    }

    pub fn parse(value: &str) -> Option<DnsMode> {
        DnsMode::ALL.into_iter().find(|mode| mode.as_str() == value)
    }
}

impl fmt::Display for DnsMode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
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
    Volume,
    PrimaryIp,
    Firewall,
    Server,
    DnsRecord,
}

impl ResourceKind {
    /// the kind of a declared resource, by the lookup type of its reference
    fn of(resource: &crate::api::ResourceRef) -> Option<ResourceKind> {
        use std::any::TypeId;
        let kinds = [
            (TypeId::of::<HetznerSshKeyLookup>(), ResourceKind::SshKey),
            (TypeId::of::<HetznerVolumeLookup>(), ResourceKind::Volume),
            (
                TypeId::of::<HetznerPrimaryIpLookup>(),
                ResourceKind::PrimaryIp,
            ),
            (
                TypeId::of::<crate::hetzner::firewall::HetznerFirewallLookup>(),
                ResourceKind::Firewall,
            ),
            (
                TypeId::of::<crate::hetzner::server::HetznerServerLookup>(),
                ResourceKind::Server,
            ),
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
    /// the location of the server, none without one
    pub location: Option<Location>,
    /// the dns mode on its resources, none without one
    pub dns_mode: Option<DnsMode>,
    pub resources: Vec<ResourceState>,
}

/// the diff of a deployment: every resource a deploy declares (ssh key, data volume, primary
/// ips, firewall, server and dns records), declared from the labels and the settings the
/// deployment carries (`DeploymentSpec::declared_by`) and diffed like a plan does. What a deploy
/// would create shows as missing, what it would change with its changes
pub async fn state(
    provider: &HetznerProviderRuntime,
    deployment: &DiscoveredDeployment,
) -> Result<DeploymentState> {
    let spec = DeploymentSpec::declared_by(deployment)?;
    let diffs = spec.registry(provider)?.diff_all().await?;
    let resources: Vec<ResourceState> = diffs
        .into_iter()
        .filter_map(|diff| {
            let kind = ResourceKind::of(&diff.resource)?;
            let detail = detail(deployment, kind, &diff.resource.name);
            Some(resource_state(
                kind,
                &diff.resource.name.clone(),
                diff,
                detail,
            ))
        })
        .collect();

    let status = if resources.iter().any(|resource| {
        matches!(
            resource.status,
            DiffStatus::Missing | DiffStatus::ParentMissing
        )
    }) {
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
        name: deployment.name.clone(),
        status,
        // the server's, else the volume's or the addresses'; the first when they disagree
        location: deployment
            .server
            .as_ref()
            .map(|server| server.location)
            .or_else(|| deployment.volumes.first().map(|volume| volume.location))
            .or_else(|| deployment.primary_ips.first().map(|ip| ip.location)),
        dns_mode: deployment.dns_mode(),
        resources,
    })
}

/// what there is to know of a resource at a glance: the fingerprint of the key, the size of the
/// volume, the address of a primary ip, the rules of the firewall, the type, location and address
/// of the server
fn detail(deployment: &DiscoveredDeployment, kind: ResourceKind, name: &str) -> Option<String> {
    match kind {
        ResourceKind::SshKey => deployment
            .ssh_key
            .as_ref()
            .filter(|key| key.name == name)
            .map(|key| key.fingerprint.clone()),
        ResourceKind::Volume => deployment
            .volumes
            .iter()
            .find(|volume| volume.name == name)
            .map(|volume| format!("{} GB", volume.size_gb)),
        ResourceKind::PrimaryIp => deployment
            .primary_ips
            .iter()
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

/// sets the label `key` of the server `id` to `value`, or removes it without one; every other label
/// of the server stays as it is
pub async fn set_server_label(
    api: &HetznerApi,
    id: u64,
    key: &str,
    value: Option<&str>,
) -> Result<()> {
    let server = api
        .servers()
        .get(id)
        .await?
        .ok_or_else(|| Error::message(format!("the server {id} is gone")))?;
    let mut labels = server.labels;
    match value {
        Some(value) => labels.insert(key.to_string(), value.to_string()),
        None => labels.remove(key),
    };
    api.servers()
        .update(
            id,
            &crate::hetzner::api::servers::ServerUpdate {
                labels: Some(labels),
                ..Default::default()
            },
        )
        .await?;
    Ok(())
}

/// the cloud-init a deployment's server is created with, until the install of sak is there
pub const USER_DATA: &str = "#cloud-config\npackage_update: true\n";

/// the prefix of the cloud name of every resource of a deployment, `sak-<name>`
pub const RESOURCE_PREFIX: &str = "sak-";

/// the suffix of the data volume's cloud name, `sak-<name>-data`, the longest cloud name of a
/// resource
pub const DATA_VOLUME_SUFFIX: &str = "-data";

/// the longest a dns label, and so a hostname, may be
pub const DNS_LABEL_LEN: usize = 63;

/// the longest name: the longest cloud name, `sak-<name>-data`, stays a dns label
pub const MAX_NAME_LEN: usize = DNS_LABEL_LEN - RESOURCE_PREFIX.len() - DATA_VOLUME_SUFFIX.len();

/// a name: 1 to MAX_NAME_LEN lower case letters, digits and dashes, a letter or digit at both
/// ends, what hetzner takes as a name and dns as a label, also with the prefix and a suffix
pub fn check_name(name: &str) -> Result<()> {
    let valid = !name.is_empty()
        && name.len() <= MAX_NAME_LEN
        && name
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
        && !name.starts_with('-')
        && !name.ends_with('-');
    if valid {
        Ok(())
    } else {
        Err(Error::message(format!(
            "'{name}' is no valid name: 1 to {MAX_NAME_LEN} lower case letters, digits and dashes, starting and ending with a letter or digit"
        )))
    }
}

/// what a deployment consists of, as the wizard creates it: an ssh key, a data volume, a
/// primary ipv4 and ipv6, a firewall and the server made of them, every one labeled with
/// the name
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
    /// on every resource, how dns is set up for the deployment
    pub dns_mode: DnsMode,
    /// on every resource, the dns zone the deployment's names are in; none for a destroy
    pub root_domain: Option<String>,
    /// on every resource, for the wizard to check a password against
    pub password_hash: Option<String>,
    /// on every resource, next to the name label
    pub labels: Labels,
    /// the hash of the user data instead of the user data itself, for a spec declared from a
    /// server that exists (`declared_by`)
    pub user_data_hash: Option<String>,
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
            dns_mode: DnsMode::default(),
            root_domain: None,
            password_hash: None,
            labels: Labels::new(),
            user_data_hash: None,
        })
    }

    /// the spec a deployment was deployed with, as far as its resources tell: its key, location,
    /// server type, image, volume size, user data and the dns mode, root domain and password hash
    /// on its labels. What none of its resources tells takes the default of a new deployment
    pub fn declared_by(deployment: &DiscoveredDeployment) -> Result<Self> {
        let server = deployment.server.as_ref();
        let location = deployment
            .location()
            .map_err(Error::message)?
            .unwrap_or(Location::nbg1);
        let mut spec = DeploymentSpec::new(
            &deployment.name,
            deployment
                .ssh_key
                .as_ref()
                .map(|key| key.public_key.as_str())
                .unwrap_or_default(),
            location,
            server
                .map(|server| server.server_type)
                .unwrap_or(ServerType::cx23),
        )?
        .with_dns_mode(deployment.dns_mode().unwrap_or_default());
        if let Some(root_domain) = deployment.root_domain() {
            spec = spec.with_root_domain(&root_domain);
        }
        if let Some(hash) = deployment.label(PASSWORD_HASH_LABEL) {
            spec = spec.with_password_hash(hash);
        }
        if let Some(server) = server {
            spec.image = server.image.clone();
            spec.user_data_hash = HetznerLabels::from_raw(server.labels.clone())
                .labels()
                .get(USER_DATA_LABEL)
                .cloned();
        }
        if let Some(volume) = deployment.volumes.first() {
            spec.data_volume_gb = volume.size_gb;
        }
        Ok(spec)
    }

    pub fn with_password_hash(mut self, hash: &str) -> Self {
        self.password_hash = Some(hash.to_string());
        self
    }

    pub fn with_dns_mode(mut self, dns_mode: DnsMode) -> Self {
        self.dns_mode = dns_mode;
        self
    }

    pub fn with_root_domain(mut self, root_domain: &str) -> Self {
        self.root_domain = Some(root_domain.to_string());
        self
    }

    pub fn with_labels(mut self, labels: Labels) -> Self {
        self.labels = labels;
        self
    }

    /// the labels every resource carries: the name label and the extra ones
    pub fn labels(&self) -> Labels {
        let mut labels = self.labels.clone();
        labels.insert(NAME_LABEL.to_string(), self.name.clone());
        labels
    }

    /// the labels a resource carries: those of `labels`, the dns mode, the root domain and the
    /// password hash; the firewall selects its servers by `labels` alone
    fn resource_labels(&self) -> Labels {
        let mut labels = self.labels();
        labels.insert(DNS_MODE_LABEL.to_string(), self.dns_mode.to_string());
        if let Some(root_domain) = &self.root_domain {
            labels.insert(ROOT_DOMAIN_LABEL.to_string(), root_domain.clone());
        }
        if let Some(hash) = &self.password_hash {
            labels.insert(PASSWORD_HASH_LABEL.to_string(), hash.clone());
        }
        labels
    }

    /// the cloud name of the deployment's resources, `sak-<name>`; the volume and the primary
    /// ips add what they are, `sak-<name>-data`
    pub fn resource_name(&self) -> String {
        format!("{RESOURCE_PREFIX}{}", self.name)
    }

    pub fn ssh_key(&self) -> HetznerSshKey {
        HetznerSshKey::new(&self.resource_name(), &self.public_key)
            .with_labels(self.resource_labels())
            .with_identity(self.identity())
    }

    pub fn data_volume(&self) -> HetznerVolume {
        HetznerVolume::new(
            &format!("{}{DATA_VOLUME_SUFFIX}", self.resource_name()),
            self.location,
            self.data_volume_gb,
        )
        .with_labels(self.resource_labels())
        .with_identity(self.identity())
    }

    pub fn ipv4(&self) -> HetznerPrimaryIp {
        HetznerPrimaryIp::ipv4(&format!("{}-ipv4", self.resource_name()), self.location)
            .with_labels(self.resource_labels())
            .with_identity(self.identity())
    }

    pub fn ipv6(&self) -> HetznerPrimaryIp {
        HetznerPrimaryIp::ipv6(&format!("{}-ipv6", self.resource_name()), self.location)
            .with_labels(self.resource_labels())
            .with_identity(self.identity())
    }

    /// ssh, https and icmp from anywhere, applied to the servers of the deployment
    pub fn firewall(&self) -> HetznerFirewall {
        HetznerFirewall::new(
            &self.resource_name(),
            vec![inbound_tcp("22"), inbound_tcp("443"), inbound_icmp()],
        )
        .applied_to(self.labels())
        .with_labels(self.resource_labels())
        .with_identity(self.identity())
    }

    pub fn server(&self) -> HetznerServer {
        let mut server = HetznerServer::new(
            &self.resource_name(),
            self.location,
            self.server_type,
            &self.user_data,
        )
        .with_image(&self.image)
        .with_ssh_keys(vec![self.ssh_key().as_lookup()])
        .with_volumes(vec![self.data_volume().as_lookup()])
        .with_ipv4(self.ipv4().as_lookup())
        .with_ipv6(self.ipv6().as_lookup())
        .with_labels(self.resource_labels())
        .with_identity(self.identity());
        if let Some(hash) = &self.user_data_hash {
            server.user_data = UserData::Hash(hash.clone());
        }
        server
    }

    /// the names the deployment manages in its root domain: `@` and `*` in domain mode,
    /// `<name>` and `*.<name>` in subdomain mode
    pub fn record_names(&self) -> [String; 2] {
        match self.dns_mode {
            DnsMode::Domain => ["@".to_string(), "*".to_string()],
            DnsMode::Subdomain => [self.name.clone(), format!("*.{}", self.name)],
        }
    }

    /// an A record to the primary ipv4 and an AAAA record to the primary ipv6 for each
    /// managed name, in the root domain; none without a root domain
    pub fn dns_records(&self) -> Vec<HetznerDnsRecord> {
        let Some(root_domain) = &self.root_domain else {
            return Vec::new();
        };
        let zone = HetznerDnsZoneLookup::new(root_domain);
        self.record_names()
            .iter()
            .flat_map(|name| {
                [
                    (RRType::A, self.ipv4().as_lookup()),
                    (RRType::AAAA, self.ipv6().as_lookup()),
                ]
                .map(|(rr_type, ip)| {
                    HetznerDnsRecord::new(
                        name,
                        zone.clone(),
                        rr_type,
                        vec![RecordValue::PrimaryIp(ip)],
                    )
                    .with_labels(self.resource_labels())
                    .with_identity(self.identity())
                })
            })
            .collect()
    }

    /// what identifies a resource of the deployment: the name and the password hash. Every
    /// resource carries them as labels and as identity, so a lookup refuses a resource of the
    /// name without them, and the registries destroy nothing else
    pub fn identity(&self) -> Labels {
        let mut identity = Labels::from([(NAME_LABEL.to_string(), self.name.clone())]);
        if let Some(hash) = &self.password_hash {
            identity.insert(PASSWORD_HASH_LABEL.to_string(), hash.clone());
        }
        identity
    }

    /// the resource group with its dns records in a registry of the provider, ready to diff
    /// and apply. Only the server and the ssh key may be created anew (the key holds nothing,
    /// a new key needs a new server anyway); the data volume and the primary ips never are, a
    /// change that would need it stops the plan (`RecreateRefused`). A recreate removes only
    /// resources with the identity of the deployment
    pub fn registry(&self, provider: &HetznerProviderRuntime) -> Result<Registry> {
        let mut registry = provider
            .registry()
            .owned_by(self.identity())
            .recreatable(self.server().resource_ref())
            .recreatable(self.ssh_key().resource_ref())
            .with(self.ssh_key())?
            .with(self.data_volume())?
            .with(self.ipv4())?
            .with(self.ipv6())?
            .with(self.firewall())?
            .with(self.server())?;
        for record in self.dns_records() {
            registry.add(record)?;
        }
        Ok(registry)
    }

    /// what a destroy removes: the resource group, the data volume only with `delete_data`, each
    /// only with the identity of the deployment; the dns records are found by their labels
    /// instead (`deployment_records`, `stale_records`)
    pub fn destroy_registry(
        &self,
        provider: &HetznerProviderRuntime,
        delete_data: bool,
    ) -> Result<Registry> {
        let mut registry = provider
            .registry()
            .owned_by(self.identity())
            .with(self.ssh_key())?;
        if delete_data {
            registry.add(self.data_volume())?;
        }
        registry
            .with(self.ipv4())?
            .with(self.ipv6())?
            .with(self.firewall())?
            .with(self.server())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::Lookup;
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
                    server(1, "sak-alpha", named("alpha")),
                    server(2, "other", json!({})),
                ],
            ),
            (
                "ssh_keys",
                vec![
                    key(7, "sak-alpha", named("alpha")),
                    key(
                        8,
                        "sak-beta",
                        json!({ NAME_LABEL: "beta", DNS_MODE_LABEL: "subdomain" }),
                    ),
                ],
            ),
            ("firewalls", vec![firewall(5, "sak-alpha", named("alpha"))]),
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
    async fn the_state_shows_every_resource_a_deploy_declares() {
        let (_server, api) = project().await;
        let provider =
            HetznerProviderRuntime::from_api(api.clone(), Location::hel1, ServerType::cx23);
        let deployments = discover(&api).await.unwrap();

        // beta has only its ssh key: everything else a deploy declares shows as missing
        let beta = state(&provider, &deployments[1]).await.unwrap();
        assert_eq!(beta.status, DeploymentStatus::Incomplete);
        let resources: Vec<(ResourceKind, &str, DiffStatus)> = beta
            .resources
            .iter()
            .map(|resource| (resource.kind, resource.name.as_str(), resource.status))
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
        let provider =
            HetznerProviderRuntime::from_api(api.clone(), Location::hel1, ServerType::cx23);
        let deployments = discover(&api).await.unwrap();

        let alpha = state(&provider, &deployments[0]).await.unwrap();

        let records: Vec<&str> = alpha
            .resources
            .iter()
            .filter(|resource| resource.kind == ResourceKind::DnsRecord)
            .map(|resource| resource.name.as_str())
            .collect();
        // @ and * of the domain mode, each an A and an AAAA record
        assert_eq!(records.len(), 4, "{:?}", alpha.resources);
        assert_eq!(
            serde_json::to_value(ResourceKind::DnsRecord).unwrap(),
            json!("dns_record")
        );
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
        let provider =
            HetznerProviderRuntime::from_api(api.clone(), Location::hel1, ServerType::cx23);
        let deployments = discover(&api).await.unwrap();

        let alpha = state(&provider, &deployments[0]).await.unwrap();

        // the server shows that it was created with another key
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
        assert_eq!(
            serde_json::to_value(DeploymentStatus::Changed).unwrap(),
            json!("changed")
        );
        let _ = sha256_hex;
    }

    #[test]
    fn the_dns_mode_is_on_every_resource_and_read_back_from_them() {
        let spec = DeploymentSpec::new(
            "alpha",
            "ssh-ed25519 AAAA",
            Location::hel1,
            ServerType::cx23,
        )
        .unwrap()
        .with_dns_mode(DnsMode::Domain);
        for labels in [
            spec.ssh_key().labels,
            spec.data_volume().labels,
            spec.ipv4().labels,
            spec.ipv6().labels,
            spec.firewall().labels,
            spec.server().labels,
        ] {
            assert_eq!(labels[DNS_MODE_LABEL], "domain");
        }

        let mut deployment = DiscoveredDeployment::new("alpha");
        assert_eq!(deployment.dns_mode(), None);
        let key: crate::hetzner::api::ssh_keys::SshKey = serde_json::from_value(key(
            7,
            "alpha",
            json!({ NAME_LABEL: "alpha", DNS_MODE_LABEL: "domain" }),
        ))
        .unwrap();
        deployment.ssh_key = Some(key.into());
        assert_eq!(deployment.dns_mode(), Some(DnsMode::Domain));

        assert_eq!(DnsMode::parse("subdomain"), Some(DnsMode::Subdomain));
        assert_eq!(DnsMode::parse("whole"), None);
        assert_eq!(
            serde_json::to_value(DnsMode::Domain).unwrap(),
            json!("domain")
        );
    }

    /// the zone pelle.io with rrsets of the name, type and labels given
    fn zone_with(rrsets: &[(&str, &str, Labels)]) -> Vec<HetznerDnsZoneListing> {
        vec![HetznerDnsZoneListing {
            zone: crate::hetzner::dns_zone::HetznerDnsZoneRuntime {
                id: 1,
                name: "pelle.io".to_string(),
                ttl: 3600,
                status: "ok".to_string(),
                nameservers: vec![],
                delete_protected: false,
                labels: Labels::new(),
            },
            rrsets: rrsets
                .iter()
                .map(|(name, rr_type, labels)| {
                    serde_json::from_value(json!({
                        "id": format!("{name}/{rr_type}"), "name": name, "type": rr_type, "ttl": null,
                        "protection": { "change": false }, "records": [{ "value": "x" }], "zone": 1,
                        "labels": labels
                    }))
                    .unwrap()
                })
                .collect(),
        }]
    }

    #[test]
    fn a_record_of_any_type_at_a_managed_name_is_in_the_way() {
        let subdomain = DeploymentSpec::new("www", "k", Location::hel1, ServerType::cx23)
            .unwrap()
            .with_dns_mode(DnsMode::Subdomain)
            .with_root_domain("pelle.io")
            .with_password_hash("abc");
        // of another owner at the names of the deployment: whatever the type, it stops the plan
        for rr_type in ["CNAME", "MX", "TXT", "A"] {
            assert_eq!(
                foreign_records(&zone_with(&[("www", rr_type, Labels::new())]), &subdomain),
                vec![format!("dns record 'www.pelle.io/{rr_type}'")],
                "{rr_type}"
            );
        }
        assert_eq!(
            foreign_records(&zone_with(&[("*.www", "TXT", Labels::new())]), &subdomain).len(),
            1
        );
        // the deployment's own records, and names it does not manage, are in nobody's way
        let own = subdomain.identity();
        assert!(
            foreign_records(
                &zone_with(&[("www", "A", own.clone()), ("mail", "MX", Labels::new())]),
                &subdomain
            )
            .is_empty()
        );
        // a record with the name of the deployment but another password is someone else's
        let mut other = own.clone();
        other.insert(PASSWORD_HASH_LABEL.to_string(), "xyz".to_string());
        assert_eq!(
            foreign_records(&zone_with(&[("www", "AAAA", other)]), &subdomain).len(),
            1
        );

        // domain mode: the zone keeps its own records at the apex, a CNAME or an address there
        // of another owner stops the plan
        let domain = subdomain.clone().with_dns_mode(DnsMode::Domain);
        let apex = zone_with(&[
            ("@", "SOA", Labels::new()),
            ("@", "NS", Labels::new()),
            ("@", "MX", Labels::new()),
            ("@", "TXT", Labels::new()),
        ]);
        assert!(foreign_records(&apex, &domain).is_empty());
        assert_eq!(
            foreign_records(&zone_with(&[("@", "A", Labels::new())]), &domain),
            vec!["dns record '@.pelle.io/A'".to_string()]
        );
        assert_eq!(
            foreign_records(&zone_with(&[("*", "MX", Labels::new())]), &domain).len(),
            1
        );
    }

    /// labels of an entry: the dns mode and, when given, the name
    fn entry(name: Option<&str>, mode: &str) -> Labels {
        let mut labels = Labels::from([(DNS_MODE_LABEL.to_string(), mode.to_string())]);
        if let Some(name) = name {
            labels.insert(NAME_LABEL.to_string(), name.to_string());
        }
        labels
    }

    /// the root domain `pelle.io` with the labels of its zone and of its rrsets
    fn domain(zone_labels: Labels, rrset_labels: Vec<Labels>) -> RootDomain {
        RootDomain::of(&HetznerDnsZoneListing {
            zone: crate::hetzner::dns_zone::HetznerDnsZoneRuntime {
                id: 1,
                name: "pelle.io".to_string(),
                ttl: 3600,
                status: "ok".to_string(),
                nameservers: vec![],
                delete_protected: false,
                labels: zone_labels,
            },
            rrsets: rrset_labels
                .iter()
                .enumerate()
                .map(|(index, labels)| {
                    serde_json::from_value(crate::hetzner::dns_zone::provisioner_tests::rrset(
                        1,
                        &format!("r{index}"),
                        serde_json::to_value(labels).unwrap(),
                    ))
                    .unwrap()
                })
                .collect(),
        })
    }

    #[test]
    fn a_root_domain_nobody_uses_takes_both_modes() {
        let free = domain(Labels::new(), vec![Labels::new()]);
        assert!(free.uses.is_empty());
        assert_eq!(free.check("alpha", DnsMode::Domain), Ok(()));
        assert_eq!(free.check("alpha", DnsMode::Subdomain), Ok(()));
        assert_eq!(
            free.dns_modes("alpha"),
            vec![DnsMode::Domain, DnsMode::Subdomain]
        );
    }

    #[test]
    fn a_root_domain_of_a_domain_deployment_takes_no_other_deployment() {
        let taken = domain(Labels::new(), vec![entry(Some("beta"), "domain")]);
        let by = vec![Some("beta".to_string())];
        assert_eq!(
            taken.check("alpha", DnsMode::Domain),
            Err(RootDomainConflict::DomainTaken { by: by.clone() })
        );
        assert_eq!(
            taken.check("alpha", DnsMode::Subdomain),
            Err(RootDomainConflict::MixedModes {
                used: DnsMode::Domain,
                by
            })
        );
        assert!(taken.dns_modes("alpha").is_empty());
    }

    #[test]
    fn a_root_domain_with_subdomain_deployments_takes_only_subdomains() {
        let shared = domain(
            Labels::new(),
            vec![
                entry(Some("beta"), "subdomain"),
                entry(Some("gamma"), "subdomain"),
            ],
        );
        assert_eq!(
            shared.check("alpha", DnsMode::Domain),
            Err(RootDomainConflict::MixedModes {
                used: DnsMode::Subdomain,
                by: vec![Some("beta".to_string()), Some("gamma".to_string())]
            })
        );
        assert_eq!(shared.check("alpha", DnsMode::Subdomain), Ok(()));
        assert_eq!(shared.dns_modes("alpha"), vec![DnsMode::Subdomain]);
    }

    #[test]
    fn the_entries_of_the_deployment_itself_do_not_count() {
        // deployed again in domain mode, or changed to subdomain
        let own_domain = domain(Labels::new(), vec![entry(Some("alpha"), "domain")]);
        assert_eq!(
            own_domain.dns_modes("alpha"),
            vec![DnsMode::Domain, DnsMode::Subdomain]
        );
        // the only subdomain deployment changes to domain
        let own_subdomain = domain(Labels::new(), vec![entry(Some("alpha"), "subdomain")]);
        assert_eq!(own_subdomain.check("alpha", DnsMode::Domain), Ok(()));
        // with another subdomain deployment next to it, it cannot
        let shared = domain(
            Labels::new(),
            vec![
                entry(Some("alpha"), "subdomain"),
                entry(Some("beta"), "subdomain"),
            ],
        );
        assert_eq!(shared.dns_modes("alpha"), vec![DnsMode::Subdomain]);
    }

    #[test]
    fn the_zone_and_entries_without_a_name_count_as_well() {
        let zone_labeled = domain(entry(Some("beta"), "domain"), vec![]);
        assert!(zone_labeled.dns_modes("alpha").is_empty());

        let unnamed = domain(Labels::new(), vec![entry(None, "domain")]);
        assert_eq!(
            unnamed.check("alpha", DnsMode::Subdomain),
            Err(RootDomainConflict::MixedModes {
                used: DnsMode::Domain,
                by: vec![None]
            })
        );
    }

    #[test]
    fn entries_without_a_known_dns_mode_are_left_out_and_each_deployment_counts_once() {
        let mut name_only = Labels::new();
        name_only.insert(NAME_LABEL.to_string(), "beta".to_string());
        let found = domain(
            Labels::new(),
            vec![
                name_only,
                entry(Some("gamma"), "whole"),
                entry(Some("delta"), "subdomain"),
                entry(Some("delta"), "subdomain"),
            ],
        );
        assert_eq!(
            found.uses,
            vec![DomainUse {
                name: Some("delta".to_string()),
                dns_mode: DnsMode::Subdomain
            }]
        );
    }

    #[tokio::test]
    async fn the_root_domains_are_the_zones_of_the_project() {
        use crate::hetzner::dns_zone::provisioner_tests::{mock_zones, rrset, zone};
        let (_server, api) = mock_zones(&[
            (
                zone(1, "pelle.io", json!({})),
                vec![rrset(
                    1,
                    "beta",
                    json!({ NAME_LABEL: "beta", DNS_MODE_LABEL: "subdomain" }),
                )],
            ),
            (zone(2, "example.com", json!({})), vec![]),
        ])
        .await;
        let provider = HetznerProviderRuntime::from_api(api, Location::hel1, ServerType::cx23);

        let domains = root_domains(&provider).await.unwrap();

        let names: Vec<&str> = domains.iter().map(|domain| domain.name.as_str()).collect();
        assert_eq!(names, vec!["example.com", "pelle.io"]);
        assert!(domains[0].uses.is_empty());
        assert_eq!(domains[1].dns_modes("alpha"), vec![DnsMode::Subdomain]);
    }

    #[test]
    fn the_root_domain_is_on_every_resource_and_read_back_from_them() {
        let spec = DeploymentSpec::new(
            "alpha",
            "ssh-ed25519 AAAA",
            Location::hel1,
            ServerType::cx23,
        )
        .unwrap()
        .with_root_domain("pelle.io");
        for labels in [
            spec.ssh_key().labels,
            spec.data_volume().labels,
            spec.ipv4().labels,
            spec.ipv6().labels,
            spec.firewall().labels,
            spec.server().labels,
        ] {
            assert_eq!(labels[ROOT_DOMAIN_LABEL], "pelle.io");
        }
        assert_eq!(
            spec.firewall().applied_to_labels.get(ROOT_DOMAIN_LABEL),
            None
        );
        // without one, no label
        let without = DeploymentSpec::new("alpha", "k", Location::hel1, ServerType::cx23).unwrap();
        assert_eq!(without.server().labels.get(ROOT_DOMAIN_LABEL), None);

        let mut deployment = DiscoveredDeployment::new("alpha");
        assert_eq!(deployment.root_domain(), None);
        let key: crate::hetzner::api::ssh_keys::SshKey = serde_json::from_value(key(
            7,
            "alpha",
            json!({ NAME_LABEL: "alpha", ROOT_DOMAIN_LABEL: "pelle.io" }),
        ))
        .unwrap();
        deployment.ssh_key = Some(key.into());
        assert_eq!(deployment.root_domain().as_deref(), Some("pelle.io"));
    }

    fn spec(dns_mode: DnsMode) -> DeploymentSpec {
        DeploymentSpec::new(
            "alpha",
            "ssh-ed25519 AAAA",
            Location::hel1,
            ServerType::cx23,
        )
        .unwrap()
        .with_dns_mode(dns_mode)
        .with_root_domain("pelle.io")
    }

    fn record_names(records: &[HetznerDnsRecord]) -> Vec<String> {
        records
            .iter()
            .map(|record| record.as_lookup().log_text())
            .collect()
    }

    #[test]
    fn the_dns_records_follow_the_dns_mode() {
        assert_eq!(
            record_names(&spec(DnsMode::Domain).dns_records()),
            vec![
                "dns record '@.pelle.io/A'",
                "dns record '@.pelle.io/AAAA'",
                "dns record '*.pelle.io/A'",
                "dns record '*.pelle.io/AAAA'",
            ]
        );
        let subdomain = spec(DnsMode::Subdomain).dns_records();
        assert_eq!(
            record_names(&subdomain),
            vec![
                "dns record 'alpha.pelle.io/A'",
                "dns record 'alpha.pelle.io/AAAA'",
                "dns record '*.alpha.pelle.io/A'",
                "dns record '*.alpha.pelle.io/AAAA'",
            ]
        );
        // to the primary ips, by their name and the identity of the deployment
        assert_eq!(
            subdomain[0].values,
            vec![RecordValue::PrimaryIp(
                HetznerPrimaryIpLookup::new("sak-alpha-ipv4")
                    .with_identity(spec(DnsMode::Subdomain).identity())
            )]
        );
        assert_eq!(
            subdomain[1].values,
            vec![RecordValue::PrimaryIp(
                HetznerPrimaryIpLookup::new("sak-alpha-ipv6")
                    .with_identity(spec(DnsMode::Subdomain).identity())
            )]
        );
        assert_eq!(subdomain[0].labels[NAME_LABEL], "alpha");
        assert_eq!(subdomain[0].labels[DNS_MODE_LABEL], "subdomain");
        // without a root domain, no records
        let without = DeploymentSpec::new("alpha", "k", Location::hel1, ServerType::cx23).unwrap();
        assert!(without.dns_records().is_empty());
    }

    #[test]
    fn the_records_of_another_mode_or_domain_are_stale_when_they_carry_the_identity() {
        let identity = Labels::from([
            (NAME_LABEL.to_string(), "alpha".to_string()),
            (PASSWORD_HASH_LABEL.to_string(), "abc".to_string()),
        ]);
        let record = |name: &str, zone: &str, labels: &Labels| DeploymentRecord {
            lookup: HetznerDnsRecordLookup::new(name, HetznerDnsZoneLookup::new(zone), RRType::A),
            labels: labels.clone(),
        };
        let mut other_password = identity.clone();
        other_password.insert(PASSWORD_HASH_LABEL.to_string(), "xyz".to_string());
        let found = vec![
            record("@", "pelle.io", &identity),
            record("alpha", "pelle.io", &identity),
            record("alpha", "example.com", &identity),
            // the name label alone, or another password hash: not the deployment's to remove
            record(
                "www",
                "pelle.io",
                &Labels::from([(NAME_LABEL.to_string(), "alpha".to_string())]),
            ),
            record("old", "pelle.io", &other_password),
        ];
        let declared = spec(DnsMode::Subdomain)
            .with_password_hash("abc")
            .dns_records();

        let stale = stale_records(&found, &declared, &identity);

        assert_eq!(
            stale,
            vec![found[0].lookup.clone(), found[2].lookup.clone()]
        );
        // a destroy declares none, everything with the identity goes
        assert_eq!(stale_records(&found, &[], &identity).len(), 3);
    }

    #[tokio::test]
    async fn the_records_of_a_deployment_are_those_with_its_name_label() {
        use crate::hetzner::dns_zone::provisioner_tests::{mock_zones, rrset, zone};
        let (_server, api) = mock_zones(&[
            (
                zone(1, "pelle.io", json!({})),
                vec![
                    rrset(1, "alpha", json!({ NAME_LABEL: "alpha" })),
                    rrset(1, "beta", json!({ NAME_LABEL: "beta" })),
                    rrset(1, "www", json!({})),
                ],
            ),
            (
                zone(2, "example.com", json!({})),
                vec![rrset(2, "@", json!({ NAME_LABEL: "alpha" }))],
            ),
        ])
        .await;
        let provider = HetznerProviderRuntime::from_api(api, Location::hel1, ServerType::cx23);
        let listings = provider.dns_zones().list_all().await.unwrap();

        let records = deployment_records(&listings, "alpha");

        let names: Vec<String> = records
            .iter()
            .map(|record| record.lookup.log_text())
            .collect();
        assert_eq!(
            names,
            vec![
                "dns record '@.example.com/A'",
                "dns record 'alpha.pelle.io/A'"
            ]
        );
    }

    #[test]
    fn every_resource_of_a_deployment_carries_its_identity_as_label_and_lookup() {
        let spec = spec(DnsMode::Domain).with_password_hash("abc");
        let identity = spec.identity();
        assert_eq!(identity[NAME_LABEL], "alpha");
        assert_eq!(identity[PASSWORD_HASH_LABEL], "abc");
        assert_eq!(Lookup::identity(&spec.ssh_key().as_lookup()), &identity);
        assert_eq!(Lookup::identity(&spec.data_volume().as_lookup()), &identity);
        assert_eq!(Lookup::identity(&spec.ipv4().as_lookup()), &identity);
        assert_eq!(Lookup::identity(&spec.ipv6().as_lookup()), &identity);
        assert_eq!(Lookup::identity(&spec.firewall().as_lookup()), &identity);
        assert_eq!(Lookup::identity(&spec.server().as_lookup()), &identity);
        for record in spec.dns_records() {
            assert_eq!(Lookup::identity(&record.as_lookup()), &identity);
            assert_eq!(record.labels[PASSWORD_HASH_LABEL], "abc");
        }
        // the server refers to its key, volume and addresses with the identity too
        let server = spec.server();
        assert!(
            server
                .ssh_keys
                .iter()
                .all(|key| Lookup::identity(key) == &identity)
        );
        // without a password hash the name alone
        let unhashed = DeploymentSpec::new("alpha", "k", Location::hel1, ServerType::cx23).unwrap();
        assert_eq!(unhashed.identity().len(), 1);
    }

    /// a project with an unlabeled `web` server, ssh key, firewall, data volume and primary ips,
    /// all protected where hetzner allows it: none of it created by a deployment
    async fn foreign_web() -> (wiremock::MockServer, Arc<HetznerApi>) {
        let volume = json!({
            "id": 3, "name": "sak-web-data", "format": "ext4", "linux_device": "/dev/disk/by-id/scsi-0HC_Volume_3", "size": 16,
            "protection": { "delete": true }, "server": null, "status": "available",
            "location": { "id": 2, "name": "hel1" }, "labels": {}
        });
        let ip = |id: u64, name: &str, ip_type: &str| {
            json!({
                "id": id, "name": name, "ip": "1.2.3.4", "type": ip_type, "assignee_id": null, "assignee_type": "unassigned",
                "auto_delete": false, "protection": { "delete": true }, "location": { "id": 3, "name": "hel1" }, "labels": {}
            })
        };
        mock_api_all(&[
            ("servers", vec![server(1, "sak-web", json!({}))]),
            ("ssh_keys", vec![key(7, "sak-web", json!({}))]),
            ("firewalls", vec![firewall(5, "sak-web", json!({}))]),
            ("volumes", vec![volume]),
            (
                "primary_ips",
                vec![
                    ip(11, "sak-web-ipv4", "ipv4"),
                    ip(12, "sak-web-ipv6", "ipv6"),
                ],
            ),
        ])
        .await
    }

    /// what the mock was asked to change: every request but a read
    async fn writes(server: &wiremock::MockServer) -> Vec<String> {
        server
            .received_requests()
            .await
            .unwrap_or_default()
            .iter()
            .filter(|request| request.method != wiremock::http::Method::GET)
            .map(|request| format!("{} {}", request.method, request.url.path()))
            .collect()
    }

    fn web() -> DeploymentSpec {
        DeploymentSpec::new("web", "ssh-ed25519 AAAA", Location::hel1, ServerType::cx23)
            .unwrap()
            .with_password_hash("any-password-hash")
    }

    #[tokio::test]
    async fn a_destroy_leaves_the_resources_of_someone_else_alone() {
        let (mock, api) = foreign_web().await;
        let provider = HetznerProviderRuntime::from_api(api, Location::hel1, ServerType::cx23);

        let error = web()
            .destroy_registry(&provider, true)
            .unwrap()
            .destroy_all()
            .await
            .unwrap_err();

        assert!(
            matches!(&error, Error::NotOwned { resource, .. } if resource == "server 'sak-web'"),
            "{error}"
        );
        // neither deleted nor unprotected, detached or unassigned
        assert_eq!(writes(&mock).await, Vec::<String>::new());
    }

    #[tokio::test]
    async fn a_plan_stops_at_a_resource_of_someone_else_before_anything_is_created() {
        let (mock, api) = foreign_web().await;
        let provider = HetznerProviderRuntime::from_api(api, Location::hel1, ServerType::cx23);

        let error = web()
            .registry(&provider)
            .unwrap()
            .diff_all()
            .await
            .unwrap_err();

        assert!(
            matches!(&error, Error::NotOwned { resource, .. } if resource == "ssh key 'sak-web'"),
            "{error}"
        );
        assert_eq!(writes(&mock).await, Vec::<String>::new());
        // nor does an apply take it over
        let mut registry = web().registry(&provider).unwrap();
        assert!(matches!(
            registry.apply_all().await,
            Err(Error::NotOwned { .. })
        ));
        assert_eq!(writes(&mock).await, Vec::<String>::new());
    }

    #[tokio::test]
    async fn a_resource_with_the_name_label_but_another_password_hash_is_not_owned_either() {
        let (_mock, api) = mock_api_all(&[(
            "ssh_keys",
            vec![key(
                7,
                "sak-web",
                json!({ NAME_LABEL: "web", PASSWORD_HASH_LABEL: "another-hash" }),
            )],
        )])
        .await;
        let provider = HetznerProviderRuntime::from_api(api, Location::hel1, ServerType::cx23);
        let spec = web();

        let error = crate::api::LookupProvider::lookup(
            &*provider.ssh_keys(),
            &spec.ssh_key().as_lookup(),
            &crate::api::LookupProviders::new(),
        )
        .await
        .unwrap_err();

        assert_eq!(
            error.to_string(),
            "ssh key 'sak-web' exists and does not belong to this deployment: its label 'sak.pelle.io/password-hash' has another value"
        );
    }

    #[tokio::test]
    async fn a_deploy_recreates_the_server_but_never_the_data_volume_or_the_addresses() {
        // the deployment in nbg1, declared in hel1: the server and the key may follow, the
        // volume and the addresses never
        let labeled = || json!({ NAME_LABEL: "web", PASSWORD_HASH_LABEL: "any-password-hash" });
        let volume = json!({
            "id": 3, "name": "sak-web-data", "format": "ext4", "linux_device": "/dev/disk/by-id/scsi-0HC_Volume_3", "size": 16,
            "protection": { "delete": true }, "server": null, "status": "available",
            "location": { "id": 2, "name": "nbg1" }, "labels": labeled()
        });
        let (mock, api) = mock_api_all(&[
            ("servers", vec![]),
            ("ssh_keys", vec![]),
            ("firewalls", vec![]),
            ("volumes", vec![volume]),
            ("primary_ips", vec![]),
        ])
        .await;
        let provider = HetznerProviderRuntime::from_api(api, Location::hel1, ServerType::cx23);

        let error = web()
            .registry(&provider)
            .unwrap()
            .diff_all()
            .await
            .unwrap_err();

        assert!(
            matches!(&error, Error::RecreateRefused { resource, changes }
                if resource == "volume 'sak-web-data'" && changes == "location should be 'hel1' but was 'nbg1'"),
            "{error}"
        );
        assert!(matches!(
            web().registry(&provider).unwrap().apply_all().await,
            Err(Error::RecreateRefused { .. })
        ));
        assert_eq!(writes(&mock).await, Vec::<String>::new());
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
        // the server gone, the volume tells
        deployment.volumes = vec![volume.into()];
        assert_eq!(deployment.location(), Ok(Some(Location::nbg1)));
        // resources in two locations: no guess
        deployment.primary_ips = vec![ip("hel1").into()];
        assert_eq!(
            deployment.location(),
            Err("volume 'sak-alpha-data' in nbg1, primary ip 'sak-alpha-ipv4' in hel1".to_string())
        );
    }

    #[tokio::test]
    async fn a_destroy_keeps_the_data_volume_unless_asked_to_delete_it() {
        let (_server, api) = mock_api_all(&[
            ("servers", vec![]),
            ("ssh_keys", vec![]),
            ("firewalls", vec![]),
            ("volumes", vec![]),
            ("primary_ips", vec![]),
        ])
        .await;
        let provider = HetznerProviderRuntime::from_api(api, Location::hel1, ServerType::cx23);
        let spec = spec(DnsMode::Domain);
        let mut planned = Vec::new();
        for delete_data in [false, true] {
            let resources: Vec<String> = spec
                .destroy_registry(&provider, delete_data)
                .unwrap()
                .diff_all()
                .await
                .unwrap()
                .iter()
                .map(|diff| diff.resource.log_text.clone())
                .collect();
            planned.push(resources);
        }
        let (kept, deleted) = (&planned[0], &planned[1]);
        assert!(
            !kept.contains(&"volume 'sak-alpha-data'".to_string()),
            "{kept:?}"
        );
        assert_eq!(kept.len(), 5);
        assert!(deleted.contains(&"volume 'sak-alpha-data'".to_string()));
        // the records are no part of it, they are found by their labels
        assert!(
            !deleted
                .iter()
                .any(|resource| resource.starts_with("dns record"))
        );
    }

    #[test]
    fn the_frontend_is_sak_below_the_base_domain_of_the_dns_mode() {
        assert_eq!(
            frontend_host("alpha", DnsMode::Domain, "pelle.io"),
            "sak.pelle.io"
        );
        assert_eq!(
            frontend_host("alpha", DnsMode::Subdomain, "pelle.io"),
            "sak.alpha.pelle.io"
        );

        let mut deployment = DiscoveredDeployment::new("alpha");
        assert_eq!(deployment.frontend_url(), None);
        let key = |labels: Value| -> crate::hetzner::api::ssh_keys::SshKey {
            serde_json::from_value(key(7, "alpha", labels)).unwrap()
        };
        // without a dns mode on the resources, a subdomain deployment
        deployment.ssh_key =
            Some(key(json!({ NAME_LABEL: "alpha", ROOT_DOMAIN_LABEL: "pelle.io" })).into());
        assert_eq!(
            deployment.frontend_url().as_deref(),
            Some("https://sak.alpha.pelle.io")
        );
        deployment.ssh_key = Some(
            key(json!({ NAME_LABEL: "alpha", ROOT_DOMAIN_LABEL: "pelle.io", DNS_MODE_LABEL: "domain" }))
                .into(),
        );
        assert_eq!(
            deployment.frontend_url().as_deref(),
            Some("https://sak.pelle.io")
        );
    }

    #[test]
    fn a_name_is_a_lower_case_hostname_label_short_enough_for_its_resources() {
        assert!(check_name("alpha-1").is_ok());
        // sak-<name>-data is 63 characters, the longest a dns label may be
        assert_eq!(MAX_NAME_LEN, 54);
        assert!(check_name(&"a".repeat(MAX_NAME_LEN)).is_ok());
        for name in [
            "",
            "Alpha",
            "-alpha",
            "alpha-",
            "al pha",
            "a_b",
            &"a".repeat(MAX_NAME_LEN + 1),
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
        .unwrap()
        .with_password_hash("abc")
        .with_labels(Labels::from([("env".to_string(), "test".to_string())]));
        assert_eq!(spec.dns_mode, DnsMode::Subdomain);
        assert_eq!(spec.server().labels[DNS_MODE_LABEL], "subdomain");
        assert_eq!(spec.server().labels[NAME_LABEL], "alpha");
        assert_eq!(spec.server().labels[PASSWORD_HASH_LABEL], "abc");
        assert_eq!(spec.server().labels["env"], "test");
        for labels in [
            spec.ssh_key().labels,
            spec.data_volume().labels,
            spec.ipv4().labels,
            spec.ipv6().labels,
            spec.firewall().labels,
        ] {
            assert_eq!(labels[PASSWORD_HASH_LABEL], "abc");
            assert_eq!(labels[DNS_MODE_LABEL], "subdomain");
        }
        // nor by the dns mode, a change of it leaves the firewall on its servers
        assert_eq!(spec.firewall().applied_to_labels.get(DNS_MODE_LABEL), None);
        // the firewall selects the servers by name, not by the hash
        assert_eq!(
            spec.firewall().applied_to_labels.get(PASSWORD_HASH_LABEL),
            None
        );
        assert_eq!(spec.data_volume().labels["env"], "test");
        assert_eq!(spec.data_volume().name, "sak-alpha-data");

        let diffs = spec.registry(&provider).unwrap().diff_all().await.unwrap();

        let planned: Vec<(String, DiffStatus)> = diffs
            .iter()
            .map(|diff| (diff.resource.log_text.clone(), diff.status))
            .collect();
        assert_eq!(
            planned,
            vec![
                ("ssh key 'sak-alpha'".to_string(), DiffStatus::Missing),
                ("volume 'sak-alpha-data'".to_string(), DiffStatus::Missing),
                (
                    "primary ip 'sak-alpha-ipv4'".to_string(),
                    DiffStatus::Missing
                ),
                (
                    "primary ip 'sak-alpha-ipv6'".to_string(),
                    DiffStatus::Missing
                ),
                ("firewall 'sak-alpha'".to_string(), DiffStatus::Missing),
                ("server 'sak-alpha'".to_string(), DiffStatus::ParentMissing),
            ]
        );
    }
}
