//! a dns zone: the resource, its lookup and runtime, the provisioner. A zone that exists
//! already is used by its lookup alone, without declaring it

mod lookup;
mod provisioner;
mod resource;
mod runtime;

pub use lookup::HetznerDnsZoneLookup;
pub use provisioner::HetznerDnsZoneProvisioner;
pub use resource::HetznerDnsZone;
pub use runtime::HetznerDnsZoneRuntime;
