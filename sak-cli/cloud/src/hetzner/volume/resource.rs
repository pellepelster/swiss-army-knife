use crate::api::{Labeled, Labels, Resource};
use crate::hetzner::api::Location;
use crate::hetzner::api::volumes::VolumeFormat;
use crate::hetzner::volume::{HetznerVolumeLookup, HetznerVolumeRuntime};

/// a block volume in a location, attached to a server by the server resource
#[derive(Clone, Debug)]
pub struct HetznerVolume {
    pub name: String,
    /// the labels that, with the name, identify it: written with the labels, checked on every
    /// lookup (`api::identity`); a resource of the name without them is left alone
    pub identity: Labels,
    pub location: Location,
    /// in GB, 10 at least; a volume only grows, a smaller size is a recreate
    pub size_gb: u64,
    pub format: VolumeFormat,
    /// delete protection at hetzner, on by default; destroy lifts it
    pub protected: bool,
    pub labels: Labels,
}

impl HetznerVolume {
    pub fn new(name: &str, location: Location, size_gb: u64) -> Self {
        HetznerVolume {
            name: name.to_string(),
            identity: Labels::new(),
            location,
            size_gb,
            format: VolumeFormat::Ext4,
            protected: true,
            labels: Labels::new(),
        }
    }

    pub fn with_labels(mut self, labels: Labels) -> Self {
        self.labels = labels;
        self
    }

    /// the identity labels, also written as labels: a lookup of it checks them
    pub fn with_identity(mut self, identity: Labels) -> Self {
        self.labels.extend(identity.clone());
        self.identity = identity;
        self
    }

    pub fn with_protected(mut self, protected: bool) -> Self {
        self.protected = protected;
        self
    }

    pub fn with_format(mut self, format: VolumeFormat) -> Self {
        self.format = format;
        self
    }
}

impl Resource for HetznerVolume {
    type Runtime = HetznerVolumeRuntime;
    type Lookup = HetznerVolumeLookup;

    fn name(&self) -> &str {
        &self.name
    }

    fn as_lookup(&self) -> HetznerVolumeLookup {
        HetznerVolumeLookup::new(&self.name).with_identity(self.identity.clone())
    }
}

impl Labeled for HetznerVolume {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
