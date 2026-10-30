//! a block volume: the resource, its lookup and runtime, the provisioner

mod provisioner;
mod resource;
mod runtime;

pub use provisioner::HetznerVolumeProvisioner;
pub use resource::HetznerVolume;
pub use runtime::HetznerVolumeRuntime;

/// the lookup of a volume by its name
pub type HetznerVolumeLookup = crate::hetzner::lookup::NamedLookup<HetznerVolumeRuntime>;
