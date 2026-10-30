//! a server with its ssh keys, volumes and primary ips, created from an image with cloud-init
//! user data: the resource, its lookup and runtime, the provisioner

mod provisioner;
mod resource;
mod runtime;

pub use provisioner::HetznerServerProvisioner;
pub use resource::{HetznerServer, UserData};
pub use runtime::HetznerServerRuntime;

/// the lookup of a server by its name
pub type HetznerServerLookup = crate::hetzner::lookup::NamedLookup<HetznerServerRuntime>;
