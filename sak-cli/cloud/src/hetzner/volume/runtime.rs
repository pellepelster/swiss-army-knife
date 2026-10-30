use crate::api::{Labeled, Labels, Runtime};
use crate::hetzner::api::Location;
use crate::hetzner::api::volumes::{Volume, VolumeStatus};

#[derive(Clone, Debug)]
pub struct HetznerVolumeRuntime {
    pub id: u64,
    pub name: String,
    /// `/dev/disk/by-id/scsi-0HC_Volume_<id>` on the server
    pub device: String,
    pub size_gb: u64,
    pub location: Location,
    /// the id of the server the volume is attached to
    pub server: Option<u64>,
    pub delete_protected: bool,
    /// `creating` until the api finished it; a server can take it only when it is available
    pub status: VolumeStatus,
    pub labels: Labels,
}

impl From<Volume> for HetznerVolumeRuntime {
    fn from(volume: Volume) -> Self {
        HetznerVolumeRuntime {
            id: volume.id,
            name: volume.name,
            device: volume.linux_device,
            size_gb: volume.size,
            location: volume.location.name,
            server: volume.server,
            delete_protected: volume.protection.delete,
            status: volume.status,
            labels: volume.labels,
        }
    }
}

impl Runtime for HetznerVolumeRuntime {}

impl crate::hetzner::lookup::NamedRuntime for HetznerVolumeRuntime {
    const KIND: &'static str = "volume";
}

impl Labeled for HetznerVolumeRuntime {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
