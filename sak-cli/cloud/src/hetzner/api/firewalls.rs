use crate::api::Labels;
use crate::hetzner::api::model::{Action, ActionsResponse, ListQuery, list_page};
use crate::hetzner::api::{HetznerApi, Result};
use serde::{Deserialize, Serialize};
use std::fmt;

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "lowercase")]
pub enum RuleDirection {
    In,
    Out,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "lowercase")]
pub enum RuleProtocol {
    Tcp,
    Udp,
    Icmp,
    Esp,
    Gre,
}

impl fmt::Display for RuleProtocol {
    /// `TCP`, as in the log text of a rule
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let name = match self {
            RuleProtocol::Tcp => "TCP",
            RuleProtocol::Udp => "UDP",
            RuleProtocol::Icmp => "ICMP",
            RuleProtocol::Esp => "ESP",
            RuleProtocol::Gre => "GRE",
        };
        f.write_str(name)
    }
}

/// one rule of a firewall. A diff compares the rules as sets, so the order in which the api
/// returns them has no effect
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq, Hash)]
pub struct FirewallRule {
    pub direction: RuleDirection,
    pub protocol: RuleProtocol,
    /// a port or a range `80-85`; none for icmp, esp and gre
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub port: Option<String>,
    /// cidrs, for `In`
    #[serde(default)]
    pub source_ips: Vec<String>,
    /// cidrs, for `Out`
    #[serde(default)]
    pub destination_ips: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

impl FirewallRule {
    /// `'0.0.0.0/0', '::/0' -> TCP/80`
    pub fn log_text(&self) -> String {
        let quoted = |ips: &[String]| {
            ips.iter()
                .map(|ip| format!("'{ip}'"))
                .collect::<Vec<_>>()
                .join(", ")
        };
        let target = match self.protocol {
            RuleProtocol::Icmp => "ICMP".to_string(),
            protocol => format!("{protocol}/{}", self.port.as_deref().unwrap_or("<none>")),
        };
        match self.direction {
            RuleDirection::In => format!("{} -> {target}", quoted(&self.source_ips)),
            RuleDirection::Out => format!(
                "{} -> {} {target}",
                quoted(&self.source_ips),
                quoted(&self.destination_ips)
            ),
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FirewallResourceType {
    Server,
    LabelSelector,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct ServerReference {
    pub id: u64,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct LabelSelector {
    /// `app=sak,env=prod`
    pub selector: String,
}

/// what a firewall is applied to: a server, or every server that matches a label selector
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct FirewallResource {
    #[serde(rename = "type")]
    pub resource_type: FirewallResourceType,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server: Option<ServerReference>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label_selector: Option<LabelSelector>,
}

impl FirewallResource {
    pub fn server(id: u64) -> Self {
        FirewallResource {
            resource_type: FirewallResourceType::Server,
            server: Some(ServerReference { id }),
            label_selector: None,
        }
    }

    /// the servers with all of `labels`
    pub fn label_selector(labels: &Labels) -> Self {
        let selector = labels
            .iter()
            .map(|(key, value)| format!("{key}={value}"))
            .collect::<Vec<_>>()
            .join(",");
        FirewallResource {
            resource_type: FirewallResourceType::LabelSelector,
            server: None,
            label_selector: Some(LabelSelector { selector }),
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
pub struct Firewall {
    pub id: u64,
    pub name: String,
    #[serde(default)]
    pub rules: Vec<FirewallRule>,
    #[serde(default)]
    pub applied_to: Vec<FirewallResource>,
    #[serde(default)]
    pub labels: Labels,
}

#[derive(Clone, Debug, Serialize)]
pub struct FirewallCreate {
    pub name: String,
    pub rules: Vec<FirewallRule>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub apply_to: Vec<FirewallResource>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
}

#[derive(Clone, Debug, Default, Serialize)]
pub struct FirewallUpdate {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub labels: Option<Labels>,
}

#[derive(Serialize)]
struct SetRulesRequest<'a> {
    rules: &'a [FirewallRule],
}

#[derive(Serialize)]
struct ApplyToRequest<'a> {
    apply_to: &'a [FirewallResource],
}

#[derive(Serialize)]
struct RemoveFromRequest<'a> {
    remove_from: &'a [FirewallResource],
}

#[derive(Deserialize)]
struct FirewallResponse {
    firewall: Firewall,
}

/// the answer to a create: the firewall and the actions that apply it to resources
#[derive(Debug, Deserialize)]
pub struct FirewallCreated {
    pub firewall: Firewall,
    #[serde(default)]
    pub actions: Vec<Action>,
}

list_page!(FirewallsPage, firewalls, Firewall);

pub struct FirewallsApi<'a> {
    pub(crate) api: &'a HetznerApi,
}

impl FirewallsApi<'_> {
    pub async fn list(&self, query: &ListQuery) -> Result<Vec<Firewall>> {
        self.api.list_all::<FirewallsPage>("firewalls", query).await
    }

    pub async fn get(&self, id: u64) -> Result<Option<Firewall>> {
        Ok(self
            .api
            .get::<FirewallResponse>(&format!("firewalls/{id}"), &[])
            .await?
            .map(|response| response.firewall))
    }

    pub async fn get_by_name(&self, name: &str) -> Result<Option<Firewall>> {
        Ok(self.list(&ListQuery::name(name)).await?.into_iter().next())
    }

    pub async fn create(&self, request: &FirewallCreate) -> Result<FirewallCreated> {
        self.api.post("firewalls", Some(request)).await
    }

    pub async fn update(&self, id: u64, request: &FirewallUpdate) -> Result<Firewall> {
        Ok(self
            .api
            .put::<FirewallResponse, _>(&format!("firewalls/{id}"), request)
            .await?
            .firewall)
    }

    /// a firewall still applied to resources cannot be deleted
    pub async fn delete(&self, id: u64) -> Result<bool> {
        self.api.delete(&format!("firewalls/{id}")).await
    }

    /// replaces all rules; one action per server that the firewall is applied to
    pub async fn set_rules(&self, id: u64, rules: &[FirewallRule]) -> Result<Vec<Action>> {
        let request = SetRulesRequest { rules };
        Ok(self
            .api
            .post::<ActionsResponse, _>(
                &format!("firewalls/{id}/actions/set_rules"),
                Some(&request),
            )
            .await?
            .actions)
    }

    pub async fn apply_to_resources(
        &self,
        id: u64,
        resources: &[FirewallResource],
    ) -> Result<Vec<Action>> {
        let request = ApplyToRequest {
            apply_to: resources,
        };
        Ok(self
            .api
            .post::<ActionsResponse, _>(
                &format!("firewalls/{id}/actions/apply_to_resources"),
                Some(&request),
            )
            .await?
            .actions)
    }

    pub async fn remove_from_resources(
        &self,
        id: u64,
        resources: &[FirewallResource],
    ) -> Result<Vec<Action>> {
        let request = RemoveFromRequest {
            remove_from: resources,
        };
        Ok(self
            .api
            .post::<ActionsResponse, _>(
                &format!("firewalls/{id}/actions/remove_from_resources"),
                Some(&request),
            )
            .await?
            .actions)
    }

    pub async fn wait_for_action(&self, action: &Action) -> Result<Action> {
        self.api.wait_for_action("firewalls", action.id).await
    }

    /// waits for each action; the first error stops the wait
    pub async fn wait_for_actions(&self, actions: &[Action]) -> Result<()> {
        for action in actions {
            self.wait_for_action(action).await?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rule() -> FirewallRule {
        FirewallRule {
            direction: RuleDirection::In,
            protocol: RuleProtocol::Tcp,
            port: Some("80".to_string()),
            source_ips: vec!["0.0.0.0/0".to_string(), "::/0".to_string()],
            destination_ips: vec![],
            description: Some("allow http".to_string()),
        }
    }

    #[test]
    fn rules_round_trip_through_json() {
        let json = serde_json::to_value(rule()).unwrap();

        assert_eq!(json["direction"], "in");
        assert_eq!(json["protocol"], "tcp");
        assert_eq!(json["port"], "80");
        assert_eq!(json["destination_ips"], serde_json::json!([]));
        assert_eq!(
            serde_json::from_value::<FirewallRule>(json).unwrap(),
            rule()
        );
    }

    #[test]
    fn rules_log_text() {
        assert_eq!(rule().log_text(), "'0.0.0.0/0', '::/0' -> TCP/80");
        let icmp = FirewallRule {
            protocol: RuleProtocol::Icmp,
            port: None,
            ..rule()
        };
        assert_eq!(icmp.log_text(), "'0.0.0.0/0', '::/0' -> ICMP");
    }

    #[test]
    fn label_selector_resource() {
        let labels = Labels::from([
            ("app".to_string(), "sak".to_string()),
            ("env".to_string(), "prod".to_string()),
        ]);
        let resource = FirewallResource::label_selector(&labels);

        let json = serde_json::to_value(&resource).unwrap();
        assert_eq!(json["type"], "label_selector");
        assert_eq!(json["label_selector"]["selector"], "app=sak,env=prod");
        assert!(json.get("server").is_none());
    }
}
