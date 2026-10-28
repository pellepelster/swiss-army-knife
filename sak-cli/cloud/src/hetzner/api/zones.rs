//! dns: zones and their rrsets (one name and type with its records). Every change runs
//! as an action, polled at `zones/actions/<id>`

use crate::api::Labels;
use crate::hetzner::api::model::{
    Action, ActionResponse, ChangeProtectionRequest, ListQuery, Protection, list_page,
};
use crate::hetzner::api::{HetznerApi, Result};
use serde::{Deserialize, Serialize};
use std::fmt;

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ZoneMode {
    /// the records are kept here
    Primary,
    /// the records are transferred from other nameservers
    Secondary,
}

#[derive(Clone, Debug, Deserialize)]
pub struct AuthoritativeNameservers {
    /// the nameservers hetzner assigned, to set at the registrar
    #[serde(default)]
    pub assigned: Vec<String>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Zone {
    pub id: u64,
    /// `pelle.io`, lower case, no trailing dot
    pub name: String,
    pub mode: ZoneMode,
    /// the default ttl of the rrsets, seconds
    pub ttl: u64,
    pub protection: Protection,
    /// `ok`, `updating`, `error`
    pub status: String,
    #[serde(default)]
    pub authoritative_nameservers: Option<AuthoritativeNameservers>,
    #[serde(default)]
    pub record_count: Option<u64>,
    #[serde(default)]
    pub labels: Labels,
}

#[derive(Clone, Debug, Serialize)]
pub struct ZoneCreate {
    pub name: String,
    pub mode: ZoneMode,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ttl: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
}

#[derive(Clone, Debug, Default, Serialize)]
pub struct ZoneUpdate {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
}

#[derive(Deserialize)]
struct ZoneResponse {
    zone: Zone,
}

/// the create answers with the zone and the action setting it up
#[derive(Debug, Deserialize)]
pub struct ZoneCreated {
    pub zone: Zone,
    pub action: Option<Action>,
}

#[derive(Serialize)]
struct ChangeTtlRequest {
    ttl: u64,
}

list_page!(ZonesPage, zones, Zone);

pub struct ZonesApi<'a> {
    pub(crate) api: &'a HetznerApi,
}

impl<'a> ZonesApi<'a> {
    pub async fn list(&self, query: &ListQuery) -> Result<Vec<Zone>> {
        self.api.list_all::<ZonesPage>("zones", query).await
    }

    /// by id or name
    pub async fn get(&self, zone: &str) -> Result<Option<Zone>> {
        Ok(self
            .api
            .get::<ZoneResponse>(&format!("zones/{zone}"), &[])
            .await?
            .map(|response| response.zone))
    }

    pub async fn create(&self, request: &ZoneCreate) -> Result<ZoneCreated> {
        self.api.post("zones", Some(request)).await
    }

    pub async fn update(&self, zone: &str, request: &ZoneUpdate) -> Result<Zone> {
        Ok(self
            .api
            .put::<ZoneResponse, _>(&format!("zones/{zone}"), request)
            .await?
            .zone)
    }

    /// the action deleting the zone with its rrsets, `None` when there is no such zone
    pub async fn delete(&self, zone: &str) -> Result<Option<Action>> {
        self.api.delete_with_action(&format!("zones/{zone}")).await
    }

    pub async fn change_ttl(&self, zone: &str, ttl: u64) -> Result<Action> {
        let request = ChangeTtlRequest { ttl };
        Ok(self
            .api
            .post::<ActionResponse, _>(&format!("zones/{zone}/actions/change_ttl"), Some(&request))
            .await?
            .action)
    }

    pub async fn change_protection(&self, zone: &str, delete: bool) -> Result<Action> {
        let request = ChangeProtectionRequest {
            delete,
            rebuild: None,
        };
        Ok(self
            .api
            .post::<ActionResponse, _>(
                &format!("zones/{zone}/actions/change_protection"),
                Some(&request),
            )
            .await?
            .action)
    }

    pub async fn wait_for_action(&self, action: &Action) -> Result<Action> {
        self.api.wait_for_action("zones", action.id).await
    }

    /// the rrsets of a zone, by its id or name
    pub fn rrsets(&self, zone: &str) -> RRSetsApi<'a> {
        RRSetsApi {
            api: self.api,
            zone: zone.to_string(),
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq, Hash)]
#[allow(clippy::upper_case_acronyms)]
pub enum RRType {
    A,
    AAAA,
    CAA,
    CNAME,
    DS,
    HINFO,
    HTTPS,
    MX,
    NS,
    PTR,
    RP,
    SOA,
    SRV,
    SVCB,
    TLSA,
    TXT,
}

impl fmt::Display for RRType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{self:?}")
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq, Hash)]
pub struct Record {
    pub value: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub comment: Option<String>,
}

impl Record {
    pub fn new(value: &str) -> Self {
        Record {
            value: value.to_string(),
            comment: None,
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
pub struct ChangeProtection {
    pub change: bool,
}

/// the records of one name and type in a zone, `www/A`
#[derive(Clone, Debug, Deserialize)]
pub struct RRSet {
    /// `<name>/<type>`
    pub id: String,
    /// relative to the zone, `@` for the zone itself
    pub name: String,
    #[serde(rename = "type")]
    pub rr_type: RRType,
    /// seconds, the zone's default when none
    pub ttl: Option<u64>,
    pub protection: ChangeProtection,
    #[serde(default)]
    pub records: Vec<Record>,
    /// the id of the zone
    pub zone: u64,
    #[serde(default)]
    pub labels: Labels,
}

#[derive(Clone, Debug, Serialize)]
pub struct RRSetCreate {
    pub name: String,
    #[serde(rename = "type")]
    pub rr_type: RRType,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ttl: Option<u64>,
    pub records: Vec<Record>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
}

#[derive(Clone, Debug, Default, Serialize)]
pub struct RRSetUpdate {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
}

#[derive(Serialize)]
struct SetRecordsRequest<'a> {
    records: &'a [Record],
}

#[derive(Deserialize)]
struct RRSetResponse {
    rrset: RRSet,
}

/// the create answers with the rrset and the action publishing it
#[derive(Debug, Deserialize)]
pub struct RRSetCreated {
    pub rrset: RRSet,
    pub action: Option<Action>,
}

list_page!(RRSetsPage, rrsets, RRSet);

pub struct RRSetsApi<'a> {
    api: &'a HetznerApi,
    zone: String,
}

impl RRSetsApi<'_> {
    fn path(&self, rest: &str) -> String {
        format!("zones/{}/rrsets{rest}", self.zone)
    }

    pub async fn list(&self, query: &ListQuery) -> Result<Vec<RRSet>> {
        self.api.list_all::<RRSetsPage>(&self.path(""), query).await
    }

    pub async fn get(&self, name: &str, rr_type: RRType) -> Result<Option<RRSet>> {
        Ok(self
            .api
            .get::<RRSetResponse>(&self.path(&format!("/{name}/{rr_type}")), &[])
            .await?
            .map(|response| response.rrset))
    }

    pub async fn create(&self, request: &RRSetCreate) -> Result<RRSetCreated> {
        self.api.post(&self.path(""), Some(request)).await
    }

    pub async fn update(
        &self,
        name: &str,
        rr_type: RRType,
        request: &RRSetUpdate,
    ) -> Result<RRSet> {
        Ok(self
            .api
            .put::<RRSetResponse, _>(&self.path(&format!("/{name}/{rr_type}")), request)
            .await?
            .rrset)
    }

    /// the action deleting the rrset, `None` when there is no such rrset
    pub async fn delete(&self, name: &str, rr_type: RRType) -> Result<Option<Action>> {
        self.api
            .delete_with_action(&self.path(&format!("/{name}/{rr_type}")))
            .await
    }

    /// replaces every record
    pub async fn set_records(
        &self,
        name: &str,
        rr_type: RRType,
        records: &[Record],
    ) -> Result<Action> {
        let request = SetRecordsRequest { records };
        Ok(self
            .api
            .post::<ActionResponse, _>(
                &self.path(&format!("/{name}/{rr_type}/actions/set_records")),
                Some(&request),
            )
            .await?
            .action)
    }

    pub async fn change_ttl(&self, name: &str, rr_type: RRType, ttl: u64) -> Result<Action> {
        let request = ChangeTtlRequest { ttl };
        Ok(self
            .api
            .post::<ActionResponse, _>(
                &self.path(&format!("/{name}/{rr_type}/actions/change_ttl")),
                Some(&request),
            )
            .await?
            .action)
    }

    pub async fn wait_for_action(&self, action: &Action) -> Result<Action> {
        self.api.wait_for_action("zones", action.id).await
    }
}
