use crate::api::{Labeled, Labels, Runtime};
use crate::hetzner::api::zones::{RRSet, RRType};

#[derive(Clone, Debug)]
pub struct HetznerDnsRecordRuntime {
    /// `<name>/<type>`
    pub id: String,
    pub name: String,
    pub zone: String,
    pub rr_type: RRType,
    pub ttl: Option<u64>,
    pub values: Vec<String>,
    pub labels: Labels,
}

impl HetznerDnsRecordRuntime {
    pub fn from_rrset(rrset: RRSet, zone: &str) -> Self {
        HetznerDnsRecordRuntime {
            id: rrset.id,
            name: rrset.name,
            zone: zone.to_string(),
            rr_type: rrset.rr_type,
            ttl: rrset.ttl,
            values: rrset
                .records
                .into_iter()
                .map(|record| record.value)
                .collect(),
            labels: rrset.labels,
        }
    }
}

impl Runtime for HetznerDnsRecordRuntime {}

impl Labeled for HetznerDnsRecordRuntime {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
