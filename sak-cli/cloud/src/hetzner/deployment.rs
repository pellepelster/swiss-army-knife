//! the sak deployments of a cloud project: all resources with the name label, grouped by its
//! value, and the state of each deployment compared with its declared resource group

use crate::api::{DiffStatus, Error, Labels, Lookup, Registry, Resource, Result};
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
    ACME_CA_LABEL, DNS_MODE_LABEL, NAME_LABEL, PASSWORD_HASH_LABEL, ROOT_DOMAIN_LABEL,
    USER_DATA_LABEL, USER_MODE_LABEL, VERSION_LABEL,
};
use crate::providers::hetzner::HetznerProviderRuntime;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fmt;
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
    fn new(name: &str) -> Self {
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
    fn label(&self, key: &str) -> Option<&String> {
        self.labels().into_iter().find_map(|labels| labels.get(key))
    }
}

/// a dns zone of the project: a root domain that a deployment can use, with the deployments
/// that use it. A use is an entry (the zone or one of its rrsets) with a dns mode label
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RootDomain {
    /// `pelle.io`
    pub name: String,
    pub uses: Vec<DomainUse>,
}

/// a deployment found on the entries of a root domain
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct DomainUse {
    /// the name label of the entry; none for an entry with a dns mode but no name
    pub name: Option<String>,
    pub dns_mode: DnsMode,
}

/// why a deployment cannot use a root domain in a dns mode
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RootDomainConflict {
    /// domain mode is requested, but another deployment manages the root domain in domain
    /// mode
    DomainTaken { by: Vec<Option<String>> },
    /// other deployments use the root domain in the other mode: subdomain requested on the root
    /// domain of a domain deployment (`used` domain), or domain requested on the root domain of
    /// subdomain deployments (`used` subdomain)
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

    /// the deployments other than `name` that use the root domain in `dns_mode`
    fn others(&self, name: &str, dns_mode: DnsMode) -> Vec<Option<String>> {
        self.uses
            .iter()
            .filter(|found| found.dns_mode == dns_mode && found.name.as_deref() != Some(name))
            .map(|found| found.name.clone())
            .collect()
    }

    /// whether the deployment `name` can use the root domain in `dns_mode`. A root domain has
    /// one domain deployment or any number of subdomain deployments. The entries of the
    /// deployment itself do not count, so it can deploy again and change its mode
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

    /// the dns modes in which the deployment `name` can use the root domain; none when another
    /// deployment manages it in domain mode
    pub fn dns_modes(&self, name: &str) -> Vec<DnsMode> {
        DnsMode::ALL
            .into_iter()
            .filter(|mode| self.check(name, *mode).is_ok())
            .collect()
    }
}

/// a dns record of a deployment, found by its name label in a zone of the project
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeploymentRecord {
    pub lookup: HetznerDnsRecordLookup,
    pub labels: Labels,
}

/// the dns records with the name label `name` in the zones of the project (`list_all` of the
/// dns zones). The deployment can remove only these records
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

/// the records that block the records of `spec`, as log texts: each rrset of any type at a name
/// that the deployment manages and without a label of its identity. So a name of another owner
/// stops the plan before anything is created. At the zone apex (`@`, domain mode) only A, AAAA
/// and CNAME block; the others there (SOA, NS, MX, TXT, CAA, …) belong to the zone and stay
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

/// the records of `found` that are not in `declared`: all of them on a destroy, those of the
/// old dns mode or root domain on a deploy. Only records with every label of `identity` (the
/// name and the password hash) are included
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

/// removes the records and reports each to `on_removed`, with `false` when it was gone
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

/// the root domains of the project: every dns zone with the deployments that use it, sorted by
/// name
pub async fn root_domains(provider: &HetznerProviderRuntime) -> Result<Vec<RootDomain>> {
    Ok(provider
        .dns_zones()
        .list_all()
        .await?
        .iter()
        .map(RootDomain::of)
        .collect())
}

/// the base domain of a deployment: the root domain in domain mode, `<name>.<root domain>` in
/// subdomain mode
pub fn base_domain(name: &str, dns_mode: DnsMode, root_domain: &str) -> String {
    match dns_mode {
        DnsMode::Domain => root_domain.to_string(),
        DnsMode::Subdomain => format!("{name}.{root_domain}"),
    }
}

/// the host of the frontend of a deployment, `sak.<base domain>`, as the backend derives it
/// (`SakConfig.frontendHost`); the api is at `api.<base domain>`
pub fn frontend_host(name: &str, dns_mode: DnsMode, root_domain: &str) -> String {
    format!("sak.{}", base_domain(name, dns_mode, root_domain))
}

/// how sak sets up dns for a deployment: `domain` manages `<root domain>` and
/// `*.<root domain>`, `subdomain` (the default) manages `<name>.<root domain>` and
/// `*.<name>.<root domain>`. A root domain has one domain deployment or any number of
/// subdomain deployments (docs/development/fdr/FDR-001-dns-mode.md)
#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum DnsMode {
    Domain,
    #[default]
    Subdomain,
}

impl DnsMode {
    pub const ALL: [DnsMode; 2] = [DnsMode::Domain, DnsMode::Subdomain];

    /// the value in the label and on the command line, `domain` or `subdomain`
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
    pub fn of(resource: &crate::api::ResourceRef) -> Option<ResourceKind> {
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
/// deployment (`DeploymentSpec::declared_by`) and diffed as a plan does. What a deploy would
/// create is missing; what it would change shows its changes. A deployment that cannot be
/// compared (resources in two locations, a resource of its name that belongs to someone else, a
/// failed request) is `Unknown` with the error and the resources found; it never fails a list of
/// deployments
pub async fn state(
    provider: &HetznerProviderRuntime,
    deployment: &DiscoveredDeployment,
) -> DeploymentState {
    match compare(provider, deployment).await {
        Ok(resources) => {
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
            found(deployment),
            Some(error.to_string()),
        ),
    }
}

/// every resource a deploy declares, with its diff
async fn compare(
    provider: &HetznerProviderRuntime,
    deployment: &DiscoveredDeployment,
) -> Result<Vec<ResourceState>> {
    let spec = DeploymentSpec::declared_by(deployment)?;
    let diffs = spec.registry(provider)?.diff_all().await?;
    Ok(diffs
        .into_iter()
        .filter_map(|diff| {
            let kind = ResourceKind::of(&diff.resource)?;
            let name = diff.resource.name.clone();
            let detail = detail(deployment, kind, &name);
            Some(ResourceState {
                kind,
                name,
                status: Some(diff.status),
                changes: diff
                    .changes
                    .iter()
                    .map(|change| change.log_text())
                    .collect(),
                detail,
            })
        })
        .collect())
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
            .volumes
            .iter()
            .map(|volume| (ResourceKind::Volume, volume.name.as_str())),
    );
    found.extend(
        deployment
            .primary_ips
            .iter()
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
        // of the server, else of a volume or a primary ip; the first when they differ
        location: deployment
            .server
            .as_ref()
            .map(|server| server.location)
            .or_else(|| deployment.volumes.first().map(|volume| volume.location))
            .or_else(|| deployment.primary_ips.first().map(|ip| ip.location)),
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

/// sets each label `key` of the server `id` to its `value`, or removes it when the value is
/// `None`, in one request; the other labels of the server stay
pub async fn set_server_labels(
    api: &HetznerApi,
    id: u64,
    changes: &[(&str, Option<&str>)],
) -> Result<()> {
    let server = api
        .servers()
        .get(id)
        .await?
        .ok_or_else(|| Error::message(format!("the server {id} is gone")))?;
    let mut labels = server.labels;
    for (key, value) in changes {
        match value {
            Some(value) => labels.insert(key.to_string(), value.to_string()),
            None => labels.remove(*key),
        };
    }
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

/// the cloud-init of a new spec; sakctl replaces it with the cloud-init that installs the host key
pub const USER_DATA: &str = "#cloud-config\npackage_update: true\n";

/// the prefix of the cloud name of every resource of a deployment, `sak-<name>`
pub const RESOURCE_PREFIX: &str = "sak-";

/// the suffix of the cloud name of the data volume, `sak-<name>-data`: the longest cloud name
/// of a resource
pub const DATA_VOLUME_SUFFIX: &str = "-data";

/// the maximum length of a dns label, and so of a hostname label
pub const DNS_LABEL_LEN: usize = 63;

/// the maximum length of a name, so that the longest cloud name, `sak-<name>-data`, is a dns
/// label
pub const MAX_NAME_LEN: usize = DNS_LABEL_LEN - RESOURCE_PREFIX.len() - DATA_VOLUME_SUFFIX.len();

/// checks a name: 1 to MAX_NAME_LEN lower case letters, digits and dashes, with a letter or
/// digit at both ends. Hetzner accepts it as a name and dns as a label, also with the prefix
/// and a suffix
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

/// the resources of a deployment, as sakctl creates them: an ssh key, a data volume, a primary
/// ipv4 and ipv6, a firewall, the server made of them and its dns records, each with the name
/// label
#[derive(Clone, Debug)]
pub struct DeploymentSpec {
    pub name: String,
    /// the public key of the new server, `ssh-ed25519 ...`
    pub public_key: String,
    pub location: Location,
    pub server_type: ServerType,
    pub image: String,
    pub data_volume_gb: u64,
    pub user_data: String,
    /// on every resource, how sak sets up dns for the deployment
    pub dns_mode: DnsMode,
    /// on every resource, the dns zone of the names of the deployment; none for a destroy
    pub root_domain: Option<String>,
    /// on every resource, who signs in (`single`, `multi`); none for a destroy
    pub user_mode: Option<String>,
    /// on every resource, the acme ca of the certificates (`staging`); none for the default
    pub acme_ca: Option<String>,
    /// on every resource, the version of sak that creates or deploys it; none for a destroy
    pub version: Option<String>,
    /// on every resource; sakctl checks a password against it
    pub password_hash: Option<String>,
    /// on every resource, with the name label
    pub labels: Labels,
    /// the hash of the user data instead of the text, for a spec declared from an existing
    /// server (`declared_by`)
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
            user_mode: None,
            acme_ca: None,
            version: None,
            password_hash: None,
            labels: Labels::new(),
            user_data_hash: None,
        })
    }

    /// the spec of a deployment, as far as its resources tell: its key, location, server type,
    /// image, volume size, user data hash, and the dns mode, root domain, user mode, acme ca,
    /// version and password hash on its labels. The rest has the default of a new deployment
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
        spec.user_mode = deployment.user_mode();
        spec.acme_ca = deployment.acme_ca();
        spec.version = deployment.version();
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

    pub fn with_acme_ca(mut self, acme_ca: &str) -> Self {
        self.acme_ca = Some(acme_ca.to_string());
        self
    }

    pub fn with_version(mut self, version: &str) -> Self {
        self.version = Some(version.to_string());
        self
    }

    pub fn with_user_mode(mut self, user_mode: &str) -> Self {
        self.user_mode = Some(user_mode.to_string());
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

    /// the labels of every resource: the name label and `labels`
    pub fn labels(&self) -> Labels {
        let mut labels = self.labels.clone();
        labels.insert(NAME_LABEL.to_string(), self.name.clone());
        labels
    }

    /// the labels of a resource: those of `labels()`, the dns mode, the user mode, the acme ca,
    /// the root domain and the password hash. The firewall selects its servers by `labels()`
    /// alone
    fn resource_labels(&self) -> Labels {
        let mut labels = self.labels();
        labels.insert(DNS_MODE_LABEL.to_string(), self.dns_mode.to_string());
        if let Some(user_mode) = &self.user_mode {
            labels.insert(USER_MODE_LABEL.to_string(), user_mode.clone());
        }
        if let Some(acme_ca) = &self.acme_ca {
            labels.insert(ACME_CA_LABEL.to_string(), acme_ca.clone());
        }
        if let Some(root_domain) = &self.root_domain {
            labels.insert(ROOT_DOMAIN_LABEL.to_string(), root_domain.clone());
        }
        if let Some(hash) = &self.password_hash {
            labels.insert(PASSWORD_HASH_LABEL.to_string(), hash.clone());
        }
        labels
    }

    /// the info labels of a resource, written and never compared: the version
    fn resource_info(&self) -> Labels {
        self.version
            .iter()
            .map(|version| (VERSION_LABEL.to_string(), version.clone()))
            .collect()
    }

    /// the cloud name of the resources of the deployment, `sak-<name>`; the volume and the
    /// primary ips add a suffix, e.g. `sak-<name>-data`
    pub fn resource_name(&self) -> String {
        format!("{RESOURCE_PREFIX}{}", self.name)
    }

    pub fn ssh_key(&self) -> HetznerSshKey {
        HetznerSshKey::new(&self.resource_name(), &self.public_key)
            .with_labels(self.resource_labels())
            .with_identity(self.identity())
            .with_info(self.resource_info())
    }

    pub fn data_volume(&self) -> HetznerVolume {
        HetznerVolume::new(
            &format!("{}{DATA_VOLUME_SUFFIX}", self.resource_name()),
            self.location,
            self.data_volume_gb,
        )
        .with_labels(self.resource_labels())
        .with_identity(self.identity())
        .with_info(self.resource_info())
    }

    pub fn ipv4(&self) -> HetznerPrimaryIp {
        HetznerPrimaryIp::ipv4(&format!("{}-ipv4", self.resource_name()), self.location)
            .with_labels(self.resource_labels())
            .with_identity(self.identity())
            .with_info(self.resource_info())
    }

    pub fn ipv6(&self) -> HetznerPrimaryIp {
        HetznerPrimaryIp::ipv6(&format!("{}-ipv6", self.resource_name()), self.location)
            .with_labels(self.resource_labels())
            .with_identity(self.identity())
            .with_info(self.resource_info())
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
        .with_info(self.resource_info())
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
        .with_identity(self.identity())
        .with_info(self.resource_info());
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

    /// for each managed name in the root domain, an A record to the primary ipv4 and an AAAA
    /// record to the primary ipv6; none without a root domain
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
                    .with_info(self.resource_info())
                })
            })
            .collect()
    }

    /// the identity of a resource of the deployment: the name and the password hash. Every
    /// resource has them as labels and as identity, so a lookup refuses a resource of the name
    /// without them and the registries destroy no other resource
    pub fn identity(&self) -> Labels {
        let mut identity = Labels::from([(NAME_LABEL.to_string(), self.name.clone())]);
        if let Some(hash) = &self.password_hash {
            identity.insert(PASSWORD_HASH_LABEL.to_string(), hash.clone());
        }
        identity
    }

    /// the resource group with its dns records in a registry of the provider, ready to diff
    /// and apply. Only the server and the ssh key are recreatable (the key holds no data, and a
    /// new key needs a new server). A change that needs a recreate of the data volume or a
    /// primary ip stops the plan (`RecreateRefused`). A recreate removes only resources with
    /// the identity of the deployment
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

    /// what a destroy removes: the resource group, the data volume only with `delete_data`,
    /// each only with the identity of the deployment. The dns records are not in it; they are
    /// found by their labels (`deployment_records`, `stale_records`)
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

    fn volume(id: u64, name: &str, location: &str, labels: Value) -> Value {
        json!({
            "id": id, "name": name, "format": "ext4", "linux_device": format!("/dev/disk/by-id/scsi-0HC_Volume_{id}"),
            "size": 16, "protection": { "delete": true }, "server": null, "status": "available",
            "location": { "id": 2, "name": location }, "labels": labels
        })
    }

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
        let provider =
            HetznerProviderRuntime::from_api(api.clone(), Location::nbg1, ServerType::cx23);
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
        // two volumes of the name in two locations: a deploy must not guess the location
        let (_server, api) = mock_api_all(&[
            ("servers", vec![]),
            ("ssh_keys", vec![key(8, "sak-delta", named("delta"))]),
            ("firewalls", vec![]),
            (
                "volumes",
                vec![
                    volume(3, "sak-delta-data", "nbg1", named("delta")),
                    volume(4, "sak-delta-old", "hel1", named("delta")),
                ],
            ),
            ("primary_ips", vec![]),
        ])
        .await;
        let provider =
            HetznerProviderRuntime::from_api(api.clone(), Location::nbg1, ServerType::cx23);
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
                (ResourceKind::Volume, "sak-delta-old", None),
            ]
        );
        assert_eq!(delta.resources[1].detail.as_deref(), Some("16 GB"));
    }

    #[tokio::test]
    async fn the_state_shows_every_resource_a_deploy_declares() {
        let (_server, api) = project().await;
        let provider =
            HetznerProviderRuntime::from_api(api.clone(), Location::hel1, ServerType::cx23);
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
        let provider =
            HetznerProviderRuntime::from_api(api.clone(), Location::hel1, ServerType::cx23);
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

    /// the zone pelle.io with rrsets of the given name, type and labels
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
        // a record of another owner at a name of the deployment stops the plan, of any type
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
        // the records of the deployment itself and the names it does not manage do not block
        let own = subdomain.identity();
        assert!(
            foreign_records(
                &zone_with(&[("www", "A", own.clone()), ("mail", "MX", Labels::new())]),
                &subdomain
            )
            .is_empty()
        );
        // a record with the name of the deployment but another password hash is not its own
        let mut other = own.clone();
        other.insert(PASSWORD_HASH_LABEL.to_string(), "xyz".to_string());
        assert_eq!(
            foreign_records(&zone_with(&[("www", "AAAA", other)]), &subdomain).len(),
            1
        );

        // domain mode: the zone keeps its own records at the apex; a CNAME or an address record
        // of another owner there stops the plan
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

    /// the labels of an entry: the dns mode and, when given, the name
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
        // a deploy again in domain mode, or a change to subdomain
        let own_domain = domain(Labels::new(), vec![entry(Some("alpha"), "domain")]);
        assert_eq!(
            own_domain.dns_modes("alpha"),
            vec![DnsMode::Domain, DnsMode::Subdomain]
        );
        // the only subdomain deployment can change to domain
        let own_subdomain = domain(Labels::new(), vec![entry(Some("alpha"), "subdomain")]);
        assert_eq!(own_subdomain.check("alpha", DnsMode::Domain), Ok(()));
        // with another subdomain deployment on the root domain, it cannot
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
        // without a root domain, no label
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

    #[test]
    fn the_version_is_an_info_label_of_every_resource_and_read_back_from_them() {
        use crate::api::Labeled;
        let spec = DeploymentSpec::new(
            "alpha",
            "ssh-ed25519 AAAA",
            Location::hel1,
            ServerType::cx23,
        )
        .unwrap()
        .with_root_domain("pelle.io")
        .with_version("0.1.1");
        let records = spec.dns_records();
        assert!(!records.is_empty());
        // through the trait, as the diff reads them: a resource type without info would compare it
        let resources: Vec<Box<dyn Labeled>> = vec![
            Box::new(spec.ssh_key()),
            Box::new(spec.data_volume()),
            Box::new(spec.ipv4()),
            Box::new(spec.ipv6()),
            Box::new(spec.firewall()),
            Box::new(spec.server()),
        ];
        for resource in resources.into_iter().chain(
            records
                .into_iter()
                .map(|record| Box::new(record) as Box<dyn Labeled>),
        ) {
            assert_eq!(resource.labels()[VERSION_LABEL], "0.1.1");
            assert_eq!(resource.info()[VERSION_LABEL], "0.1.1");
            // the version is no declared label
            assert_eq!(resource.info().len(), 1);
        }
        // a destroy declares no version
        let without = DeploymentSpec::new("alpha", "k", Location::hel1, ServerType::cx23).unwrap();
        assert_eq!(without.server().labels.get(VERSION_LABEL), None);

        let mut deployment = DiscoveredDeployment::new("alpha");
        assert_eq!(deployment.version(), None);
        let key: crate::hetzner::api::ssh_keys::SshKey = serde_json::from_value(key(
            7,
            "alpha",
            json!({ NAME_LABEL: "alpha", VERSION_LABEL: "0.1.1" }),
        ))
        .unwrap();
        deployment.ssh_key = Some(key.into());
        assert_eq!(deployment.version().as_deref(), Some("0.1.1"));
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
        // the records point to the primary ips, by their name and the identity of the deployment
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
            // only the name label, or another password hash: the deployment does not remove these
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
        // a destroy declares no records: every record with the identity is stale
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
        // the server also refers to its key, volume and addresses with the identity
        let server = spec.server();
        assert!(
            server
                .ssh_keys
                .iter()
                .all(|key| Lookup::identity(key) == &identity)
        );
        // without a password hash, the identity is the name alone
        let unhashed = DeploymentSpec::new("alpha", "k", Location::hel1, ServerType::cx23).unwrap();
        assert_eq!(unhashed.identity().len(), 1);
    }

    /// a project with a `web` server, ssh key, firewall, data volume and primary ips without
    /// labels, protected where hetzner allows it: no deployment created them
    fn foreign_volume() -> Value {
        json!({
            "id": 3, "name": "sak-web-data", "format": "ext4", "linux_device": "/dev/disk/by-id/scsi-0HC_Volume_3", "size": 16,
            "protection": { "delete": true }, "server": null, "status": "available",
            "location": { "id": 2, "name": "hel1" }, "labels": {}
        })
    }

    fn foreign_ip(id: u64, name: &str, ip_type: &str) -> Value {
        json!({
            "id": id, "name": name, "ip": "1.2.3.4", "type": ip_type, "assignee_id": null, "assignee_type": "unassigned",
            "auto_delete": false, "protection": { "delete": true }, "location": { "id": 3, "name": "hel1" }, "labels": {}
        })
    }

    async fn foreign_web() -> (wiremock::MockServer, Arc<HetznerApi>) {
        let (volume, ip) = (foreign_volume(), foreign_ip);
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

    /// the requests to the mock that change something: all requests except reads
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
        // nothing was deleted, unprotected, detached or unassigned
        assert_eq!(writes(&mock).await, Vec::<String>::new());
    }

    /// a server, ssh key, firewall, volume or primary ip of the declared name without the labels of
    /// the deployment stops the plan, each on its own, and nothing is written
    #[tokio::test]
    async fn each_kind_of_resource_of_someone_else_stops_the_plan() {
        let kinds = ["servers", "ssh_keys", "firewalls", "volumes", "primary_ips"];
        for (kind, foreign, name) in [
            ("servers", server(1, "sak-web", json!({})), "'sak-web'"),
            ("ssh_keys", key(7, "sak-web", json!({})), "'sak-web'"),
            ("firewalls", firewall(5, "sak-web", json!({})), "'sak-web'"),
            ("volumes", foreign_volume(), "'sak-web-data'"),
            (
                "primary_ips",
                foreign_ip(11, "sak-web-ipv4", "ipv4"),
                "'sak-web-ipv4'",
            ),
        ] {
            let lists: Vec<(&str, Vec<Value>)> = kinds
                .iter()
                .map(|listed| {
                    let items = if *listed == kind {
                        vec![foreign.clone()]
                    } else {
                        Vec::new()
                    };
                    (*listed, items)
                })
                .collect();
            let (mock, api) = mock_api_all(&lists).await;
            let provider = HetznerProviderRuntime::from_api(api, Location::hel1, ServerType::cx23);

            let error = web()
                .registry(&provider)
                .unwrap()
                .diff_all()
                .await
                .unwrap_err();

            assert!(
                matches!(&error, Error::NotOwned { resource, .. } if resource.contains(name)),
                "{kind}: {error}"
            );
            assert_eq!(writes(&mock).await, Vec::<String>::new(), "{kind}");
        }
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
        // an apply does not take it over either
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
        // the deployment is in nbg1 and declared in hel1: the server and the key can be
        // recreated, the volume and the addresses cannot
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
        // the records are not in it; they are found by their labels
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
        // without a dns mode on the resources, the deployment uses subdomain
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
        // then sak-<name>-data has 63 characters, the maximum length of a dns label
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
        // not by the dns mode: a change of it keeps the firewall on its servers
        assert_eq!(spec.firewall().applied_to_labels.get(DNS_MODE_LABEL), None);
        // not by the password hash: the firewall selects the servers by the name
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
