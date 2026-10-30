use crate::api::Labels;
use crate::hetzner::api::Result;
use crate::hetzner::api::model::{Action, Location, Named, Protection};
use crate::hetzner::api::resource::{HetznerResource, Protectable, ResourceApi, WithActions};
use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum VolumeFormat {
    Ext4,
    Xfs,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum VolumeStatus {
    Available,
    Creating,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Volume {
    pub id: u64,
    pub name: String,
    pub format: Option<VolumeFormat>,
    /// the device of the volume on the server, `/dev/disk/by-id/scsi-0HC_Volume_<id>`
    pub linux_device: String,
    /// in GB
    pub size: u64,
    pub protection: Protection,
    /// the id of the server the volume is attached to
    pub server: Option<u64>,
    pub status: VolumeStatus,
    pub location: Named<Location>,
    #[serde(default)]
    pub labels: Labels,
}

#[derive(Clone, Debug, Serialize)]
pub struct VolumeCreate {
    pub name: String,
    /// in GB
    pub size: u64,
    pub location: Location,
    pub format: VolumeFormat,
    pub automount: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
}

#[derive(Clone, Debug, Default, Serialize)]
pub struct VolumeUpdate {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
}

#[derive(Serialize)]
struct AttachRequest {
    server: u64,
    automount: bool,
}

impl HetznerResource for Volume {
    const PATH: &'static str = "volumes";
    const KEY: &'static str = "volume";
    type Create = VolumeCreate;
    type Update = VolumeUpdate;
    /// the volume; the api continues the creation in an action
    type Created = Self;
    type Deleted = bool;
}

impl WithActions for Volume {}

impl Protectable for Volume {}

pub type VolumesApi<'a> = ResourceApi<'a, Volume>;

impl VolumesApi<'_> {
    pub async fn attach(&self, id: u64, server: u64, automount: bool) -> Result<Action> {
        let request = AttachRequest { server, automount };
        self.action_with(id, "attach", &request).await
    }

    pub async fn detach(&self, id: u64) -> Result<Action> {
        self.action(id, "detach").await
    }
}
