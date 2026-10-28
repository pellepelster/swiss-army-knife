//! a dns record set, the records of one name and type in a zone: the resource, its
//! lookup and runtime, the provisioner

mod lookup;
mod provisioner;
mod resource;
mod runtime;

pub use crate::hetzner::api::zones::RRType;
pub use lookup::HetznerDnsRecordLookup;
pub use provisioner::HetznerDnsRecordProvisioner;
pub use resource::{HetznerDnsRecord, RecordValue};
pub use runtime::HetznerDnsRecordRuntime;
