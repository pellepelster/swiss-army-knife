//! a primary ip, the public address that a server keeps when it is recreated: the resource,
//! its lookup and runtime, the provisioner

mod provisioner;
mod resource;
mod runtime;

pub use provisioner::HetznerPrimaryIpProvisioner;
pub use resource::HetznerPrimaryIp;
pub use runtime::HetznerPrimaryIpRuntime;

/// the lookup of a primary ip by its name
pub type HetznerPrimaryIpLookup = crate::hetzner::lookup::NamedLookup<HetznerPrimaryIpRuntime>;
