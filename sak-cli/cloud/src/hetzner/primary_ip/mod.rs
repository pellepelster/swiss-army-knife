//! a primary ip, the public address that a server keeps when it is recreated: the resource,
//! its lookup and runtime, the provisioner

mod lookup;
mod provisioner;
mod resource;
mod runtime;

pub use lookup::HetznerPrimaryIpLookup;
pub use provisioner::HetznerPrimaryIpProvisioner;
pub use resource::HetznerPrimaryIp;
pub use runtime::HetznerPrimaryIpRuntime;
