use crate::api::Labels;
use crate::hetzner::api::Result;
use crate::hetzner::api::model::{Action, Location, Named, Protection};
use crate::hetzner::api::resource::{
    CreateAnswer, HetznerResource, Protectable, ResourceApi, WithActions,
};
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

/// a primary ip can only be assigned to a server; the api reports `Unassigned` for an ip
/// without a server
#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum AssigneeType {
    Server,
    Unassigned,
}

/// a public address of a server. With `auto_delete` false it stays when the server is deleted;
/// a recreated server gets it again through `public_net` of its create request
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
    /// a server using the ip must be created in its location
    pub location: Named<Location>,
    #[serde(default)]
    pub labels: Labels,
}

#[derive(Clone, Debug, Serialize)]
pub struct PrimaryIpCreate {
    pub name: String,
    #[serde(rename = "type")]
    pub ip_type: PrimaryIpType,
    /// with `assignee_id`: the ip is created assigned to that server, in its location
    #[serde(skip_serializing_if = "Option::is_none")]
    pub assignee_type: Option<AssigneeType>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub assignee_id: Option<u64>,
    /// the location of an ip created without a server; set `assignee_id` or `location`
    #[serde(skip_serializing_if = "Option::is_none")]
    pub location: Option<Location>,
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

/// the answer to a create: the ip and, when it is created assigned, the action that assigns it
#[derive(Debug, Deserialize)]
pub struct PrimaryIpCreated {
    pub primary_ip: PrimaryIp,
    pub action: Option<Action>,
}

impl CreateAnswer for PrimaryIpCreated {
    type Answer = Self;

    fn from_answer(answer: Self) -> Self {
        answer
    }
}

impl HetznerResource for PrimaryIp {
    const PATH: &'static str = "primary_ips";
    const KEY: &'static str = "primary_ip";
    type Create = PrimaryIpCreate;
    type Update = PrimaryIpUpdate;
    type Created = PrimaryIpCreated;
    type Deleted = bool;
}

impl WithActions for PrimaryIp {}

impl Protectable for PrimaryIp {}

pub type PrimaryIpsApi<'a> = ResourceApi<'a, PrimaryIp>;

impl PrimaryIpsApi<'_> {
    /// the server must be powered off
    pub async fn assign(&self, id: u64, server: u64) -> Result<Action> {
        let request = AssignRequest {
            assignee_id: server,
            assignee_type: AssigneeType::Server,
        };
        self.action_with(id, "assign", &request).await
    }

    pub async fn unassign(&self, id: u64) -> Result<Action> {
        self.action(id, "unassign").await
    }
}
