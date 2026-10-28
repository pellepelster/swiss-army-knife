use crate::api::Labels;
use crate::hetzner::api::model::{
    Action, ActionResponse, ChangeProtectionRequest, ListQuery, Named, Protection, list_page,
};
use crate::hetzner::api::{HetznerApi, NO_BODY, Result};
use serde::{Deserialize, Serialize};
use std::fmt;

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum PrimaryIpType {
    Ipv4,
    Ipv6,
}

impl fmt::Display for PrimaryIpType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PrimaryIpType::Ipv4 => f.write_str("ipv4"),
            PrimaryIpType::Ipv6 => f.write_str("ipv6"),
        }
    }
}

/// a primary ip is only ever assigned to a server
#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum AssigneeType {
    Server,
}

/// the public address of a server; with `auto_delete` false it outlives the server, a
/// recreated server gets it back through `public_net` of its create request
#[derive(Clone, Debug, Deserialize)]
pub struct PrimaryIp {
    pub id: u64,
    pub name: String,
    /// the address, a `/64` for ipv6
    pub ip: String,
    #[serde(rename = "type")]
    pub ip_type: PrimaryIpType,
    pub assignee_id: Option<u64>,
    pub assignee_type: Option<AssigneeType>,
    pub auto_delete: bool,
    pub protection: Protection,
    /// a primary ip belongs to a datacenter, `nbg1-dc3`, not only to a location
    pub datacenter: Named<String>,
    #[serde(default)]
    pub labels: Labels,
}

#[derive(Clone, Debug, Serialize)]
pub struct PrimaryIpCreate {
    pub name: String,
    #[serde(rename = "type")]
    pub ip_type: PrimaryIpType,
    pub assignee_type: AssigneeType,
    /// created assigned to this server, in its datacenter
    #[serde(skip_serializing_if = "Option::is_none")]
    pub assignee_id: Option<u64>,
    /// created unassigned in this datacenter, one of `assignee_id` and `datacenter` is set
    #[serde(skip_serializing_if = "Option::is_none")]
    pub datacenter: Option<String>,
    pub auto_delete: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
}

#[derive(Clone, Debug, Default, Serialize)]
pub struct PrimaryIpUpdate {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub auto_delete: Option<bool>,
}

#[derive(Serialize)]
struct AssignRequest {
    assignee_id: u64,
    assignee_type: AssigneeType,
}

#[derive(Deserialize)]
struct PrimaryIpResponse {
    primary_ip: PrimaryIp,
}

/// the create answers with the ip and, when created assigned, the action assigning it
#[derive(Debug, Deserialize)]
pub struct PrimaryIpCreated {
    pub primary_ip: PrimaryIp,
    pub action: Option<Action>,
}

list_page!(PrimaryIpsPage, primary_ips, PrimaryIp);

pub struct PrimaryIpsApi<'a> {
    pub(crate) api: &'a HetznerApi,
}

impl PrimaryIpsApi<'_> {
    pub async fn list(&self, query: &ListQuery) -> Result<Vec<PrimaryIp>> {
        self.api
            .list_all::<PrimaryIpsPage>("primary_ips", query)
            .await
    }

    pub async fn get(&self, id: u64) -> Result<Option<PrimaryIp>> {
        Ok(self
            .api
            .get::<PrimaryIpResponse>(&format!("primary_ips/{id}"), &[])
            .await?
            .map(|response| response.primary_ip))
    }

    pub async fn get_by_name(&self, name: &str) -> Result<Option<PrimaryIp>> {
        Ok(self.list(&ListQuery::name(name)).await?.into_iter().next())
    }

    pub async fn create(&self, request: &PrimaryIpCreate) -> Result<PrimaryIpCreated> {
        self.api.post("primary_ips", Some(request)).await
    }

    pub async fn update(&self, id: u64, request: &PrimaryIpUpdate) -> Result<PrimaryIp> {
        Ok(self
            .api
            .put::<PrimaryIpResponse, _>(&format!("primary_ips/{id}"), request)
            .await?
            .primary_ip)
    }

    pub async fn delete(&self, id: u64) -> Result<bool> {
        self.api.delete(&format!("primary_ips/{id}")).await
    }

    pub async fn change_protection(&self, id: u64, delete: bool) -> Result<Action> {
        let request = ChangeProtectionRequest {
            delete,
            rebuild: None,
        };
        Ok(self
            .api
            .post::<ActionResponse, _>(
                &format!("primary_ips/{id}/actions/change_protection"),
                Some(&request),
            )
            .await?
            .action)
    }

    /// the server must be powered off
    pub async fn assign(&self, id: u64, server: u64) -> Result<Action> {
        let request = AssignRequest {
            assignee_id: server,
            assignee_type: AssigneeType::Server,
        };
        Ok(self
            .api
            .post::<ActionResponse, _>(&format!("primary_ips/{id}/actions/assign"), Some(&request))
            .await?
            .action)
    }

    pub async fn unassign(&self, id: u64) -> Result<Action> {
        Ok(self
            .api
            .post::<ActionResponse, _>(&format!("primary_ips/{id}/actions/unassign"), NO_BODY)
            .await?
            .action)
    }

    pub async fn wait_for_action(&self, action: &Action) -> Result<Action> {
        self.api.wait_for_action("primary_ips", action.id).await
    }
}
