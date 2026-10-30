use crate::api::{Labels, Resource};
use crate::hetzner::api::Location;
use crate::hetzner::api::volumes::VolumeFormat;
use crate::hetzner::kind::declared_labels;
use crate::hetzner::volume::{HetznerVolumeLookup, HetznerVolumeRuntime};

/// a block volume in a location; the server resource attaches it to a server
#[derive(Clone, Debug)]
pub struct HetznerVolume {
    pub name: String,
    /// the labels that, with the name, identify it (`api::identity`); every lookup checks them
    pub identity: Labels,
    /// the labels that tell about it and are never compared (`api::Labeled::info`), written with
    /// the labels when it is created or changed for another reason
    pub info: Labels,
    pub location: Location,
    /// in GB, at least 10. A volume larger than this is up to date; a smaller volume needs a
    /// recreate
    pub size_gb: u64,
    pub format: VolumeFormat,
    /// the delete protection at hetzner, on by default; destroy removes it
    pub protected: bool,
    pub labels: Labels,
}

impl HetznerVolume {
    pub fn new(name: &str, location: Location, size_gb: u64) -> Self {
        HetznerVolume {
            name: name.to_string(),
            identity: Labels::new(),
            info: Labels::new(),
            location,
            size_gb,
            format: VolumeFormat::Ext4,
            protected: true,
            labels: Labels::new(),
        }
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

declared_labels!(HetznerVolume);
