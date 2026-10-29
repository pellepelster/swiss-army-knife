use crate::api::Labels;
use crate::hetzner::api::model::{
    Action, ActionResponse, ChangeProtectionRequest, ListQuery, Location, Named, Protection,
    ServerType, list_page,
};
use crate::hetzner::api::{HetznerApi, NO_BODY, Result};
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

#[derive(Deserialize)]
struct ServerResponse {
    server: Server,
}

/// the answer to a create: the server and the action that creates it
#[derive(Debug, Deserialize)]
pub struct ServerCreated {
    pub server: Server,
    pub action: Action,
}

list_page!(ServersPage, servers, Server);

pub struct ServersApi<'a> {
    pub(crate) api: &'a HetznerApi,
}

impl ServersApi<'_> {
    pub async fn list(&self, query: &ListQuery) -> Result<Vec<Server>> {
        self.api.list_all::<ServersPage>("servers", query).await
    }

    pub async fn get(&self, id: u64) -> Result<Option<Server>> {
        Ok(self
            .api
            .get::<ServerResponse>(&format!("servers/{id}"), &[])
            .await?
            .map(|response| response.server))
    }

    pub async fn get_by_name(&self, name: &str) -> Result<Option<Server>> {
        Ok(self.list(&ListQuery::name(name)).await?.into_iter().next())
    }

    pub async fn create(&self, request: &ServerCreate) -> Result<ServerCreated> {
        self.api.post("servers", Some(request)).await
    }

    pub async fn update(&self, id: u64, request: &ServerUpdate) -> Result<Server> {
        Ok(self
            .api
            .put::<ServerResponse, _>(&format!("servers/{id}"), request)
            .await?
            .server)
    }

    /// the action that deletes the server; `None` when the server does not exist
    pub async fn delete(&self, id: u64) -> Result<Option<Action>> {
        self.api.delete_with_action(&format!("servers/{id}")).await
    }

    pub async fn change_protection(&self, id: u64, delete: bool) -> Result<Action> {
        let request = ChangeProtectionRequest {
            delete,
            rebuild: Some(delete),
        };
        Ok(self
            .api
            .post::<ActionResponse, _>(
                &format!("servers/{id}/actions/change_protection"),
                Some(&request),
            )
            .await?
            .action)
    }

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
        Ok(self
            .api
            .post::<ActionResponse, _>(&format!("servers/{id}/actions/change_type"), Some(&request))
            .await?
            .action)
    }

    pub async fn wait_for_action(&self, action: &Action) -> Result<Action> {
        self.api.wait_for_action("servers", action.id).await
    }

    async fn action(&self, id: u64, command: &str) -> Result<Action> {
        Ok(self
            .api
            .post::<ActionResponse, _>(&format!("servers/{id}/actions/{command}"), NO_BODY)
            .await?
            .action)
    }
}
