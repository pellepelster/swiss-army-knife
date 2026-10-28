//! the sak deployments of a cloud project: everything carrying the name label, grouped by
//! its value, and the state of each against the resource group a deployment consists of

use crate::api::{
    DiffStatus, Error, Labels, LookupProviders, Provisioner, Registry, Resource, ResourceDiff,
    Result,
};
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
    is_managed_label,
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
    /// the sak name on the entry, none for an entry with a dns mode but no name
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

/// the dns records labeled with the sak name `name`, in the zones of the project
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

/// the declared records that exist already without the deployment's name label, the user's
/// or another deployment's: a deployment never takes one over, so they stop it before
/// anything is created
pub fn foreign_records(
    listings: &[HetznerDnsZoneListing],
    declared: &[HetznerDnsRecord],
) -> Vec<HetznerDnsRecordLookup> {
    declared
        .iter()
        .filter(|record| {
            let lookup = record.as_lookup();
            listings
                .iter()
                .filter(|listing| listing.zone.name == lookup.zone.name)
                .flat_map(|listing| &listing.rrsets)
                .any(|rrset| {
                    rrset.name == lookup.record
                        && rrset.rr_type == lookup.rr_type
                        && rrset.labels.get(NAME_LABEL) != record.labels.get(NAME_LABEL)
                })
        })
        .map(|record| record.as_lookup())
        .collect()
}

/// the records of `found` not among `declared`: all of them on a destroy, those of the old
/// dns mode or root domain on a deploy
pub fn stale_records(
    found: &[DeploymentRecord],
    declared: &[HetznerDnsRecord],
) -> Vec<HetznerDnsRecordLookup> {
    found
        .iter()
        .map(|record| record.lookup.clone())
        .filter(|lookup| !declared.iter().any(|record| record.as_lookup() == *lookup))
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

/// how dns is set up for a deployment: `domain` manages `<root domain>` and
/// `*.<root domain>`, `subdomain` (the default) manages `<sak name>.<root domain>` and
/// `*.<sak name>.<root domain>`; a root domain has one domain deployment or any number of
/// subdomain deployments (doc/fdr/FDR-001-dns-mode.md)
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
    /// the location of the server, none without one
    pub location: Option<Location>,
    /// the dns mode on its resources, none without one
    pub dns_mode: Option<DnsMode>,
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
        location: deployment.server.as_ref().map(|server| server.location),
        dns_mode: deployment.dns_mode(),
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
    /// on every resource, how dns is set up for the deployment
    pub dns_mode: DnsMode,
    /// on every resource, the dns zone the deployment's names are in; none for a destroy
    pub root_domain: Option<String>,
    /// on every resource, for the wizard to check a password against
    pub password_hash: Option<String>,
    /// on every resource, next to the name label
    pub labels: Labels,
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
        })
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

    pub fn ssh_key(&self) -> HetznerSshKey {
        HetznerSshKey::new(&self.name, &self.public_key).with_labels(self.resource_labels())
    }

    pub fn data_volume(&self) -> HetznerVolume {
        HetznerVolume::new(
            &format!("{}-data", self.name),
            self.location,
            self.data_volume_gb,
        )
        .with_labels(self.resource_labels())
    }

    pub fn ipv4(&self) -> HetznerPrimaryIp {
        HetznerPrimaryIp::ipv4(&format!("{}-ipv4", self.name), self.location)
            .with_labels(self.resource_labels())
    }

    pub fn ipv6(&self) -> HetznerPrimaryIp {
        HetznerPrimaryIp::ipv6(&format!("{}-ipv6", self.name), self.location)
            .with_labels(self.resource_labels())
    }

    /// ssh, https and icmp from anywhere, applied to the servers of the deployment
    pub fn firewall(&self) -> HetznerFirewall {
        HetznerFirewall::new(
            &self.name,
            vec![inbound_tcp("22"), inbound_tcp("443"), inbound_icmp()],
        )
        .applied_to(self.labels())
        .with_labels(self.resource_labels())
    }

    pub fn server(&self) -> HetznerServer {
        HetznerServer::new(&self.name, self.location, self.server_type, &self.user_data)
            .with_image(&self.image)
            .with_ssh_keys(vec![self.ssh_key().as_lookup()])
            .with_volumes(vec![self.data_volume().as_lookup()])
            .with_ipv4(self.ipv4().as_lookup())
            .with_ipv6(self.ipv6().as_lookup())
            .with_labels(self.resource_labels())
    }

    /// the names the deployment manages in its root domain: `@` and `*` in domain mode,
    /// `<sak name>` and `*.<sak name>` in subdomain mode
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
                })
            })
            .collect()
    }

    /// the resource group with its dns records in a registry of the provider, ready to diff
    /// and apply
    pub fn registry(&self, provider: &HetznerProviderRuntime) -> Result<Registry> {
        let mut registry = provider
            .registry()
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

    /// what a destroy removes: the resource group, the data volume only with `delete_data`;
    /// the dns records are found by their labels instead (`deployment_records`)
    pub fn destroy_registry(
        &self,
        provider: &HetznerProviderRuntime,
        delete_data: bool,
    ) -> Result<Registry> {
        let mut registry = provider.registry().with(self.ssh_key())?;
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

    /// labels of an entry: the dns mode and, when given, the sak name
    fn entry(name: Option<&str>, mode: &str) -> Labels {
        let mut labels = Labels::from([(DNS_MODE_LABEL.to_string(), mode.to_string())]);
        if let Some(name) = name {
            labels.insert(NAME_LABEL.to_string(), name.to_string());
        }
        labels
    }

    fn zone_runtime() -> crate::hetzner::dns_zone::HetznerDnsZoneRuntime {
        crate::hetzner::dns_zone::HetznerDnsZoneRuntime {
            id: 1,
            name: "pelle.io".to_string(),
            ttl: 3600,
            status: "ok".to_string(),
            nameservers: vec![],
            delete_protected: false,
            labels: Labels::new(),
        }
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
        // to the primary ips, labeled like every resource of the deployment
        assert_eq!(
            subdomain[0].values,
            vec![RecordValue::PrimaryIp(HetznerPrimaryIpLookup::new(
                "alpha-ipv4"
            ))]
        );
        assert_eq!(
            subdomain[1].values,
            vec![RecordValue::PrimaryIp(HetznerPrimaryIpLookup::new(
                "alpha-ipv6"
            ))]
        );
        assert_eq!(subdomain[0].labels[NAME_LABEL], "alpha");
        assert_eq!(subdomain[0].labels[DNS_MODE_LABEL], "subdomain");
        // without a root domain, no records
        let without = DeploymentSpec::new("alpha", "k", Location::hel1, ServerType::cx23).unwrap();
        assert!(without.dns_records().is_empty());
    }

    #[test]
    fn the_records_of_another_mode_or_domain_are_stale() {
        let record = |name: &str, zone: &str, rr_type: RRType| DeploymentRecord {
            lookup: HetznerDnsRecordLookup::new(name, HetznerDnsZoneLookup::new(zone), rr_type),
            labels: Labels::new(),
        };
        let found = vec![
            record("@", "pelle.io", RRType::A),
            record("alpha", "pelle.io", RRType::A),
            record("alpha", "example.com", RRType::A),
        ];

        let stale = stale_records(&found, &spec(DnsMode::Subdomain).dns_records());
        assert_eq!(
            stale,
            vec![found[0].lookup.clone(), found[2].lookup.clone()]
        );
        // a destroy declares none, everything goes
        assert_eq!(stale_records(&found, &[]).len(), 3);
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
    fn records_of_the_user_or_of_another_deployment_at_the_managed_names_are_foreign() {
        let rrset =
            |name: &str, rr_type: &str, labels: Value| -> crate::hetzner::api::zones::RRSet {
                let mut rrset = crate::hetzner::dns_zone::provisioner_tests::rrset(1, name, labels);
                rrset["type"] = json!(rr_type);
                serde_json::from_value(rrset).unwrap()
            };
        let listing = |rrsets| HetznerDnsZoneListing {
            zone: zone_runtime(),
            rrsets,
        };
        let declared = spec(DnsMode::Domain).dns_records();

        // the user's records at @ and *, the MX next to them is no business of the deployment
        let user = listing(vec![
            rrset("@", "A", json!({})),
            rrset("*", "AAAA", json!({ NAME_LABEL: "beta" })),
            rrset("@", "MX", json!({})),
        ]);
        let names: Vec<String> = foreign_records(&[user], &declared)
            .iter()
            .map(|record| record.log_text())
            .collect();
        assert_eq!(
            names,
            vec!["dns record '@.pelle.io/A'", "dns record '*.pelle.io/AAAA'"]
        );

        // its own records are no obstacle, nor are records in another zone
        let own = listing(vec![rrset("@", "A", json!({ NAME_LABEL: "alpha" }))]);
        assert!(foreign_records(&[own], &declared).is_empty());
        let mut elsewhere = listing(vec![rrset("@", "A", json!({}))]);
        elsewhere.zone.name = "example.com".to_string();
        assert!(foreign_records(&[elsewhere], &declared).is_empty());
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
            !kept.contains(&"volume 'alpha-data'".to_string()),
            "{kept:?}"
        );
        assert_eq!(kept.len(), 5);
        assert!(deleted.contains(&"volume 'alpha-data'".to_string()));
        // the records are no part of it, they are found by their labels
        assert!(
            !deleted
                .iter()
                .any(|resource| resource.starts_with("dns record"))
        );
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
