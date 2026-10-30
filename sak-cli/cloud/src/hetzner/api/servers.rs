use crate::api::Labels;
use crate::hetzner::api::Result;
use crate::hetzner::api::model::{Action, Location, Named, Protection, ServerType};
use crate::hetzner::api::resource::{
    CreateAnswer, HetznerResource, Protectable, ResourceApi, WithActions,
};
use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ServerStatus {
    Running,
    Initializing,
    Starting,
    Stopping,
    Off,
    Deleting,
    Migrating,
    Rebuilding,
    Unknown,
}

/// a public address of a server, with the id of its primary ip
#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
pub struct PublicIp {
    pub id: Option<u64>,
    pub ip: String,
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq)]
pub struct PublicNet {
    pub ipv4: Option<PublicIp>,
    pub ipv6: Option<PublicIp>,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
pub struct PrivateNet {
    pub network: u64,
    pub ip: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Server {
    pub id: u64,
    pub name: String,
    pub status: ServerStatus,
    pub server_type: Named<ServerType>,
    /// no name for a deleted image
    pub image: Option<Named<Option<String>>>,
    pub location: Named<Location>,
    pub protection: Protection,
    /// the ids of the attached volumes
    #[serde(default)]
    pub volumes: Vec<u64>,
    #[serde(default)]
    pub public_net: PublicNet,
    #[serde(default)]
    pub private_net: Vec<PrivateNet>,
    #[serde(default)]
    pub labels: Labels,
}

impl Server {
    pub fn image_name(&self) -> Option<&str> {
        self.image.as_ref().and_then(|image| image.name.as_deref())
    }

    pub fn public_ipv4(&self) -> Option<&str> {
        self.public_net.ipv4.as_ref().map(|ip| ip.ip.as_str())
    }
}

/// the public addresses of a new server: existing primary ips by id, or new ones that
/// hetzner creates and deletes with the server
#[derive(Clone, Debug, Default, Serialize)]
pub struct PublicNetCreate {
    pub enable_ipv4: bool,
    pub enable_ipv6: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ipv4: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ipv6: Option<u64>,
}

#[derive(Clone, Debug, Serialize)]
pub struct ServerCreate {
    pub name: String,
    pub server_type: ServerType,
    pub image: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub location: Option<Location>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub ssh_keys: Vec<u64>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub volumes: Vec<u64>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub firewalls: Vec<u64>,
    /// cloud-init
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user_data: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub public_net: Option<PublicNetCreate>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub start_after_create: Option<bool>,
}

#[derive(Clone, Debug, Default, Serialize)]
pub struct ServerUpdate {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
}

#[derive(Serialize)]
struct ChangeTypeRequest {
    server_type: ServerType,
    upgrade_disk: bool,
}

/// the answer to a create: the server and the action that creates it
#[derive(Debug, Deserialize)]
pub struct ServerCreated {
    pub server: Server,
    pub action: Action,
}

impl CreateAnswer for ServerCreated {
    type Answer = Self;

    fn from_answer(answer: Self) -> Self {
        answer
    }
}

impl HetznerResource for Server {
    const PATH: &'static str = "servers";
    const KEY: &'static str = "server";
    type Create = ServerCreate;
    type Update = ServerUpdate;
    type Created = ServerCreated;
    /// the action that deletes the server
    type Deleted = Option<Action>;
}

impl WithActions for Server {}

impl Protectable for Server {
    const REBUILD: bool = true;
}

pub type ServersApi<'a> = ResourceApi<'a, Server>;

impl ServersApi<'_> {
    pub async fn power_on(&self, id: u64) -> Result<Action> {
        self.action(id, "poweron").await
    }

    /// turns the power off at once; `shutdown` asks the os to stop
    pub async fn power_off(&self, id: u64) -> Result<Action> {
        self.action(id, "poweroff").await
    }

    pub async fn shutdown(&self, id: u64) -> Result<Action> {
        self.action(id, "shutdown").await
    }

    pub async fn reboot(&self, id: u64) -> Result<Action> {
        self.action(id, "reboot").await
    }

    /// the server must be powered off
    pub async fn change_type(
        &self,
        id: u64,
        server_type: ServerType,
        upgrade_disk: bool,
    ) -> Result<Action> {
        let request = ChangeTypeRequest {
            server_type,
            upgrade_disk,
        };
        self.action_with(id, "change_type", &request).await
    }
}
