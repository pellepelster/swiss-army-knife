use crate::api::Labels;
use crate::hetzner::api::resource::{HetznerResource, ResourceApi};
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

impl HetznerResource for SshKey {
    const PATH: &'static str = "ssh_keys";
    const KEY: &'static str = "ssh_key";
    type Create = SshKeyCreate;
    type Update = SshKeyUpdate;
    type Created = Self;
    type Deleted = bool;
}

pub type SshKeysApi<'a> = ResourceApi<'a, SshKey>;
