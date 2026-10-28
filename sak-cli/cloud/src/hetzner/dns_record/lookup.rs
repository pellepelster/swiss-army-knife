use crate::api::Lookup;
use crate::hetzner::api::zones::RRType;
use crate::hetzner::dns_record::HetznerDnsRecordRuntime;
use crate::hetzner::dns_zone::HetznerDnsZoneLookup;

/// a record set by its name, zone and type; the name alone is not unique, so the
/// resource name carries all three: `www.example.de/A`
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HetznerDnsRecordLookup {
    pub record: String,
    pub zone: HetznerDnsZoneLookup,
    pub rr_type: RRType,
    full_name: String,
}

impl HetznerDnsRecordLookup {
    pub fn new(record: &str, zone: HetznerDnsZoneLookup, rr_type: RRType) -> Self {
        let full_name = format!("{record}.{}/{rr_type}", zone.name);
        HetznerDnsRecordLookup {
            record: record.to_string(),
            zone,
            rr_type,
            full_name,
        }
    }
}

impl Lookup for HetznerDnsRecordLookup {
    type Runtime = HetznerDnsRecordRuntime;

    fn name(&self) -> &str {
        &self.full_name
    }

    fn log_text(&self) -> String {
        format!("dns record '{}'", self.full_name)
    }
}
