use crate::api::{Labeled, Labels, Lookup, Resource, ResourceRef};
use crate::hetzner::api::zones::RRType;
use crate::hetzner::dns_record::{HetznerDnsRecordLookup, HetznerDnsRecordRuntime};
use crate::hetzner::dns_zone::HetznerDnsZoneLookup;
use crate::hetzner::primary_ip::HetznerPrimaryIpLookup;
use crate::hetzner::server::HetznerServerLookup;

/// the source of the value of a record
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RecordValue {
    /// as written, `203.0.113.1` or `mail.example.de.`
    Literal(String),
    /// the public ipv4 of the server, for an A record
    ServerIpv4(HetznerServerLookup),
    /// the address of the primary ip; for an ipv6 primary ip (a `/64`) its first address
    PrimaryIp(HetznerPrimaryIpLookup),
}

impl RecordValue {
    pub fn literal(value: &str) -> Self {
        RecordValue::Literal(value.to_string())
    }

    fn resource_ref(&self) -> Option<ResourceRef> {
        match self {
            RecordValue::Literal(_) => None,
            RecordValue::ServerIpv4(server) => Some(server.resource_ref()),
            RecordValue::PrimaryIp(ip) => Some(ip.resource_ref()),
        }
    }
}

/// the records of one name and type in a zone, `www` in `example.de` as an A record
#[derive(Clone, Debug)]
pub struct HetznerDnsRecord {
    /// relative to the zone, lower case, `@` for the zone itself
    pub name: String,
    /// the labels that, with the name, identify it (`api::identity`); every lookup checks them
    pub identity: Labels,
    /// the labels that tell about it and are never compared (`api::Labeled::info`), written with
    /// the labels when it is created or changed for another reason
    pub info: Labels,
    pub zone: HetznerDnsZoneLookup,
    pub rr_type: RRType,
    /// in seconds; `None` for the default of the zone
    pub ttl: Option<u64>,
    pub values: Vec<RecordValue>,
    pub labels: Labels,
}

impl HetznerDnsRecord {
    pub fn new(
        name: &str,
        zone: HetznerDnsZoneLookup,
        rr_type: RRType,
        values: Vec<RecordValue>,
    ) -> Self {
        HetznerDnsRecord {
            name: name.to_string(),
            identity: Labels::new(),
            info: Labels::new(),
            zone,
            rr_type,
            ttl: None,
            values,
            labels: Labels::new(),
        }
    }

    /// an A record
    pub fn a(name: &str, zone: HetznerDnsZoneLookup, values: Vec<RecordValue>) -> Self {
        Self::new(name, zone, RRType::A, values)
    }

    pub fn with_ttl(mut self, ttl: u64) -> Self {
        self.ttl = Some(ttl);
        self
    }

    pub fn with_labels(mut self, labels: Labels) -> Self {
        self.labels = labels;
        self
    }

    /// sets the identity labels and adds them to the labels
    pub fn with_identity(mut self, identity: Labels) -> Self {
        self.labels.extend(identity.clone());
        self.identity = identity;
        self
    }

    /// sets the info labels and adds them to the labels
    pub fn with_info(mut self, info: Labels) -> Self {
        self.labels.extend(info.clone());
        self.info = info;
        self
    }
}

impl Resource for HetznerDnsRecord {
    type Runtime = HetznerDnsRecordRuntime;
    type Lookup = HetznerDnsRecordLookup;

    fn name(&self) -> &str {
        &self.name
    }

    fn as_lookup(&self) -> HetznerDnsRecordLookup {
        HetznerDnsRecordLookup::new(&self.name, self.zone.clone(), self.rr_type)
            .with_identity(self.identity.clone())
    }

    fn depends_on(&self) -> Vec<ResourceRef> {
        std::iter::once(self.zone.resource_ref())
            .chain(self.values.iter().filter_map(RecordValue::resource_ref))
            .collect()
    }
}

impl Labeled for HetznerDnsRecord {
    fn labels(&self) -> &Labels {
        &self.labels
    }

    fn info(&self) -> &Labels {
        &self.info
    }
}
