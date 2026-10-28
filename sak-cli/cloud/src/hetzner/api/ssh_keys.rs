use crate::api::Labels;
use crate::hetzner::api::model::{ListQuery, list_page};
use crate::hetzner::api::{HetznerApi, Result};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Deserialize)]
pub struct SshKey {
    pub id: u64,
    pub name: String,
    /// the md5 fingerprint of the public key as hetzner computes it, `aa:bb:...`
    pub fingerprint: String,
    pub public_key: String,
    #[serde(default)]
    pub labels: Labels,
}

#[derive(Clone, Debug, Serialize)]
pub struct SshKeyCreate {
    pub name: String,
    pub public_key: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
}

#[derive(Clone, Debug, Default, Serialize)]
pub struct SshKeyUpdate {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
}

#[derive(Deserialize)]
struct SshKeyResponse {
    ssh_key: SshKey,
}

list_page!(SshKeysPage, ssh_keys, SshKey);

pub struct SshKeysApi<'a> {
    pub(crate) api: &'a HetznerApi,
}

impl SshKeysApi<'_> {
    pub async fn list(&self, query: &ListQuery) -> Result<Vec<SshKey>> {
        self.api.list_all::<SshKeysPage>("ssh_keys", query).await
    }

    pub async fn get(&self, id: u64) -> Result<Option<SshKey>> {
        Ok(self
            .api
            .get::<SshKeyResponse>(&format!("ssh_keys/{id}"), &[])
            .await?
            .map(|response| response.ssh_key))
    }

    /// names are unique per project, so at most one
    pub async fn get_by_name(&self, name: &str) -> Result<Option<SshKey>> {
        Ok(self.list(&ListQuery::name(name)).await?.into_iter().next())
    }

    pub async fn create(&self, request: &SshKeyCreate) -> Result<SshKey> {
        Ok(self
            .api
            .post::<SshKeyResponse, _>("ssh_keys", Some(request))
            .await?
            .ssh_key)
    }

    pub async fn update(&self, id: u64, request: &SshKeyUpdate) -> Result<SshKey> {
        Ok(self
            .api
            .put::<SshKeyResponse, _>(&format!("ssh_keys/{id}"), request)
            .await?
            .ssh_key)
    }

    pub async fn delete(&self, id: u64) -> Result<bool> {
        self.api.delete(&format!("ssh_keys/{id}")).await
    }
}
