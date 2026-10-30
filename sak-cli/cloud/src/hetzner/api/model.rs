//! the types that all resource apis use: locations and server types, pagination, list
//! queries, actions and protection

use crate::api::Labels;
use serde::{Deserialize, Serialize};
use std::fmt;

/// the locations of servers and volumes
#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "lowercase")]
#[allow(non_camel_case_types)]
pub enum Location {
    fsn1,
    nbg1,
    hel1,
    ash,
    hil,
    sin,
}

impl fmt::Display for Location {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{self:?}")
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "lowercase")]
#[allow(non_camel_case_types)]
pub enum ServerType {
    cpx02,
    cpx11,
    cx23,
    cx33,
    cx43,
    cx53,
    cpx12,
    cpx21,
    cpx22,
    cpx31,
    cpx32,
    cpx41,
    cpx42,
    cpx51,
    cpx52,
    cpx62,
    cax11,
    cax21,
    cax31,
    cax41,
    ccx13,
    ccx23,
    ccx33,
    ccx43,
    ccx53,
    ccx63,
}

impl fmt::Display for ServerType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{self:?}")
    }
}

/// a resource the api refers to by name inside another, e.g. the location of a server
#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
pub struct Named<T> {
    pub name: T,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
pub struct Protection {
    pub delete: bool,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Meta {
    pub pagination: Pagination,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Pagination {
    pub page: u32,
    pub per_page: u32,
    pub next_page: Option<u32>,
    pub total_entries: Option<u32>,
}

/// one page of a list response; each list endpoint has its own wrapper key
pub trait ListPage: serde::de::DeserializeOwned {
    type Item;

    fn into_parts(self) -> (Vec<Self::Item>, Meta);
}

/// declares the page of a list endpoint that is not a `HetznerResource` (those read
/// `resource::Page`): `list_page!(ZonesPage, zones, Zone)` reads
/// `{ "zones": [...], "meta": {...} }`
macro_rules! list_page {
    ($page:ident, $key:ident, $item:ty) => {
        #[derive(serde::Deserialize)]
        pub struct $page {
            $key: Vec<$item>,
            meta: $crate::hetzner::api::model::Meta,
        }

        impl $crate::hetzner::api::model::ListPage for $page {
            type Item = $item;

            fn into_parts(self) -> (Vec<$item>, $crate::hetzner::api::model::Meta) {
                (self.$key, self.meta)
            }
        }
    };
}

pub(crate) use list_page;

/// the filters of a list request: `name=backend`, `label_selector=env==prod`
#[derive(Clone, Debug, Default)]
pub struct ListQuery {
    filters: Vec<(String, String)>,
    label_selectors: Vec<String>,
}

impl ListQuery {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn name(name: &str) -> Self {
        Self::new().filter("name", name)
    }

    pub fn filter(mut self, attribute: &str, value: &str) -> Self {
        self.filters
            .push((attribute.to_string(), value.to_string()));
        self
    }

    /// resources with the label `key` set to `value`
    pub fn label(mut self, key: &str, value: &str) -> Self {
        self.label_selectors.push(format!("{key}=={value}"));
        self
    }

    /// resources with the label `key`, with any value
    pub fn label_exists(mut self, key: &str) -> Self {
        self.label_selectors.push(key.to_string());
        self
    }

    /// resources with all of `labels`
    pub fn labels(self, labels: &Labels) -> Self {
        labels
            .iter()
            .fold(self, |query, (key, value)| query.label(key, value))
    }

    pub(crate) fn params(&self, page: u32, per_page: u32) -> Vec<(String, String)> {
        let mut params = vec![
            ("page".to_string(), page.to_string()),
            ("per_page".to_string(), per_page.to_string()),
        ];
        params.extend(self.filters.iter().cloned());
        if !self.label_selectors.is_empty() {
            params.push(("label_selector".to_string(), self.label_selectors.join(",")));
        }
        params
    }
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ActionStatus {
    Running,
    Success,
    Error,
}

#[derive(Clone, Debug, Deserialize)]
pub struct ActionError {
    pub code: String,
    pub message: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct ActionResource {
    pub id: u64,
    #[serde(rename = "type")]
    pub resource_type: String,
}

/// a change that the api runs in the background; `wait_for_action` polls it until it ends
#[derive(Clone, Debug, Deserialize)]
pub struct Action {
    pub id: u64,
    pub command: String,
    pub status: ActionStatus,
    pub progress: u32,
    pub error: Option<ActionError>,
    #[serde(default)]
    pub resources: Vec<ActionResource>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ActionResponse {
    pub action: Action,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ActionsResponse {
    pub actions: Vec<Action>,
}

#[derive(Serialize)]
pub(crate) struct ChangeProtectionRequest {
    pub delete: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rebuild: Option<bool>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn list_query_params() {
        let query = ListQuery::name("backend")
            .label("env", "prod")
            .label("app", "sak")
            .label_exists("sak.pelle.io/name");

        assert_eq!(
            query.params(2, 50),
            vec![
                ("page".to_string(), "2".to_string()),
                ("per_page".to_string(), "50".to_string()),
                ("name".to_string(), "backend".to_string()),
                (
                    "label_selector".to_string(),
                    "env==prod,app==sak,sak.pelle.io/name".to_string()
                ),
            ]
        );
    }

    #[test]
    fn locations_and_server_types_serialize_lowercase() {
        assert_eq!(serde_json::to_string(&Location::nbg1).unwrap(), "\"nbg1\"");
        assert_eq!(
            serde_json::to_string(&ServerType::cx23).unwrap(),
            "\"cx23\""
        );
        assert_eq!(
            serde_json::from_str::<ServerType>("\"cax11\"").unwrap(),
            ServerType::cax11
        );
        assert_eq!(Location::hel1.to_string(), "hel1");
    }
}
