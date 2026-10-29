use crate::api::Labels;
use crate::hetzner::api::model::{
    Action, ActionResponse, ChangeProtectionRequest, ListQuery, Location, Named, Protection,
    list_page,
};
use crate::hetzner::api::{HetznerApi, NO_BODY, Result};
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

#[derive(Deserialize)]
struct VolumeResponse {
    volume: Volume,
}

list_page!(VolumesPage, volumes, Volume);

pub struct VolumesApi<'a> {
    pub(crate) api: &'a HetznerApi,
}

impl VolumesApi<'_> {
    pub async fn list(&self, query: &ListQuery) -> Result<Vec<Volume>> {
        self.api.list_all::<VolumesPage>("volumes", query).await
    }

    pub async fn get(&self, id: u64) -> Result<Option<Volume>> {
        Ok(self
            .api
            .get::<VolumeResponse>(&format!("volumes/{id}"), &[])
            .await?
            .map(|response| response.volume))
    }

    pub async fn get_by_name(&self, name: &str) -> Result<Option<Volume>> {
        Ok(self.list(&ListQuery::name(name)).await?.into_iter().next())
    }

    /// the volume; the api continues the creation in an action
    pub async fn create(&self, request: &VolumeCreate) -> Result<Volume> {
        Ok(self
            .api
            .post::<VolumeResponse, _>("volumes", Some(request))
            .await?
            .volume)
    }

    pub async fn update(&self, id: u64, request: &VolumeUpdate) -> Result<Volume> {
        Ok(self
            .api
            .put::<VolumeResponse, _>(&format!("volumes/{id}"), request)
            .await?
            .volume)
    }

    pub async fn delete(&self, id: u64) -> Result<bool> {
        self.api.delete(&format!("volumes/{id}")).await
    }

    pub async fn change_protection(&self, id: u64, delete: bool) -> Result<Action> {
        let request = ChangeProtectionRequest {
            delete,
            rebuild: None,
        };
        Ok(self
            .api
            .post::<ActionResponse, _>(
                &format!("volumes/{id}/actions/change_protection"),
                Some(&request),
            )
            .await?
            .action)
    }

    pub async fn attach(&self, id: u64, server: u64, automount: bool) -> Result<Action> {
        let request = AttachRequest { server, automount };
        Ok(self
            .api
            .post::<ActionResponse, _>(&format!("volumes/{id}/actions/attach"), Some(&request))
            .await?
            .action)
    }

    pub async fn detach(&self, id: u64) -> Result<Action> {
        Ok(self
            .api
            .post::<ActionResponse, _>(&format!("volumes/{id}/actions/detach"), NO_BODY)
            .await?
            .action)
    }

    pub async fn wait_for_action(&self, action: &Action) -> Result<Action> {
        self.api.wait_for_action("volumes", action.id).await
    }
}
