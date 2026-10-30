//! the resources of a deployment as a deploy declares them, the names and the registries

use crate::api::{Error, Labels, Registry, Resource, Result};
use crate::hetzner::api::zones::RRType;
use crate::hetzner::api::{Location, ServerType};
use crate::hetzner::deployment::{AcmeCa, DiscoveredDeployment, DnsMode, UserMode};
use crate::hetzner::dns_record::{HetznerDnsRecord, RecordValue};
use crate::hetzner::dns_zone::HetznerDnsZoneLookup;
use crate::hetzner::firewall::{HetznerFirewall, inbound_icmp, inbound_tcp};
use crate::hetzner::labels::HetznerLabels;
use crate::hetzner::primary_ip::HetznerPrimaryIp;
use crate::hetzner::server::{HetznerServer, UserData};
use crate::hetzner::ssh_key::HetznerSshKey;
use crate::hetzner::volume::HetznerVolume;
use crate::hetzner::{
    ACME_CA_LABEL, DNS_MODE_LABEL, NAME_LABEL, PASSWORD_HASH_LABEL, ROOT_DOMAIN_LABEL,
    USER_DATA_LABEL, USER_MODE_LABEL, VERSION_LABEL,
};
use crate::providers::hetzner::HetznerProviderRuntime;

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

/// what a destroy does with the data volume of the deployment
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DataVolume {
    Keep,
    Delete,
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
    /// on every resource, who signs in; none for a destroy
    pub user_mode: Option<UserMode>,
    /// on every resource, the acme ca of the certificates; none for the default
    pub acme_ca: Option<AcmeCa>,
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

    pub fn with_acme_ca(mut self, acme_ca: AcmeCa) -> Self {
        self.acme_ca = Some(acme_ca);
        self
    }

    pub fn with_version(mut self, version: &str) -> Self {
        self.version = Some(version.to_string());
        self
    }

    pub fn with_user_mode(mut self, user_mode: UserMode) -> Self {
        self.user_mode = Some(user_mode);
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
        if let Some(user_mode) = self.user_mode {
            labels.insert(USER_MODE_LABEL.to_string(), user_mode.to_string());
        }
        if let Some(acme_ca) = self.acme_ca {
            labels.insert(ACME_CA_LABEL.to_string(), acme_ca.to_string());
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

    /// what a destroy removes: the resource group, the data volume only with
    /// `DataVolume::Delete`, each only with the identity of the deployment. The dns records are
    /// not in it; they are found by their labels (`deployment_records`, `stale_records`)
    pub fn destroy_registry(
        &self,
        provider: &HetznerProviderRuntime,
        data_volume: DataVolume,
    ) -> Result<Registry> {
        let mut registry = provider
            .registry()
            .owned_by(self.identity())
            .with(self.ssh_key())?;
        if data_volume == DataVolume::Delete {
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
    use crate::api::{DiffStatus, Error, Labels, Lookup, Resource};
    use crate::hetzner::api::{Location, ServerType};
    use crate::hetzner::deployment::fixtures::*;
    use crate::hetzner::deployment::*;
    use crate::hetzner::dns_record::{HetznerDnsRecord, RecordValue};
    use crate::hetzner::primary_ip::HetznerPrimaryIpLookup;
    use crate::hetzner::test_support::mock_api_all;
    use crate::hetzner::{
        DNS_MODE_LABEL, NAME_LABEL, PASSWORD_HASH_LABEL, ROOT_DOMAIN_LABEL, VERSION_LABEL,
    };
    use crate::providers::hetzner::HetznerProviderRuntime;
    use serde_json::{Value, json};

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

    #[tokio::test]
    async fn a_destroy_leaves_the_resources_of_someone_else_alone() {
        let (mock, api) = foreign_web().await;
        let provider = HetznerProviderRuntime::from_api(api);

        let error = web()
            .destroy_registry(&provider, DataVolume::Delete)
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
            let provider = HetznerProviderRuntime::from_api(api);

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
        let provider = HetznerProviderRuntime::from_api(api);

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
        let provider = HetznerProviderRuntime::from_api(api);
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
        let provider = HetznerProviderRuntime::from_api(api);

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
        let provider = HetznerProviderRuntime::from_api(api);
        let spec = spec(DnsMode::Domain);
        let mut planned = Vec::new();
        for data_volume in [DataVolume::Keep, DataVolume::Delete] {
            let resources: Vec<String> = spec
                .destroy_registry(&provider, data_volume)
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
        let provider = HetznerProviderRuntime::from_api(api.clone());
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
