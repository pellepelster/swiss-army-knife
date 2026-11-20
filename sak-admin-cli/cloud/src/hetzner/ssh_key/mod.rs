//! an ssh key: the resource, its lookup and runtime, the provisioner

mod provisioner;
mod resource;
mod runtime;

pub use provisioner::{HetznerSshKeyProvisioner, fingerprint};
pub use resource::HetznerSshKey;
pub use runtime::HetznerSshKeyRuntime;

/// the lookup of a ssh key by its name
pub type HetznerSshKeyLookup = crate::hetzner::lookup::NamedLookup<HetznerSshKeyRuntime>;
