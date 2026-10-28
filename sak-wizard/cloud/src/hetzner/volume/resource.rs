use crate::api::{Labeled, Labels, Resource};
use crate::hetzner::api::Location;
use crate::hetzner::api::volumes::VolumeFormat;
use crate::hetzner::volume::{HetznerVolumeLookup, HetznerVolumeRuntime};

/// a block volume in a location, attached to a server by the server resource
#[derive(Clone, Debug)]
pub struct HetznerVolume {
    pub name: String,
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
        HetznerVolumeLookup::new(&self.name)
    }
}

impl Labeled for HetznerVolume {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
