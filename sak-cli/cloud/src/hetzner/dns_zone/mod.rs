//! a dns zone: the resource, its lookup and runtime, the provisioner. An existing zone can be
//! used through its lookup alone, without a declaration

mod provisioner;
mod resource;
mod runtime;

pub use provisioner::{HetznerDnsZoneListing, HetznerDnsZoneProvisioner};

#[cfg(test)]
pub(crate) use provisioner::tests as provisioner_tests;
pub use resource::HetznerDnsZone;
pub use runtime::HetznerDnsZoneRuntime;

/// the lookup of a dns zone by its name
pub type HetznerDnsZoneLookup = crate::hetzner::lookup::NamedLookup<HetznerDnsZoneRuntime>;
