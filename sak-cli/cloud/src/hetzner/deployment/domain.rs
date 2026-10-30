//! the root domains of a project and the dns mode in which a deployment uses one; the names of
//! a deployment below its root domain

use crate::api::Result;
use crate::hetzner::dns_zone::HetznerDnsZoneListing;
use crate::hetzner::{DNS_MODE_LABEL, NAME_LABEL};
use crate::providers::hetzner::HetznerProviderRuntime;
use serde::{Deserialize, Serialize};
use std::fmt;

/// a dns zone of the project: a root domain that a deployment can use, with the deployments
/// that use it. A use is an entry (the zone or one of its rrsets) with a dns mode label
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RootDomain {
    /// `pelle.io`
    pub name: String,
    pub uses: Vec<DomainUse>,
}

/// a deployment found on the entries of a root domain
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct DomainUse {
    /// the name label of the entry; none for an entry with a dns mode but no name
    pub name: Option<String>,
    pub dns_mode: DnsMode,
}

/// why a deployment cannot use a root domain in a dns mode
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RootDomainConflict {
    /// domain mode is requested, but another deployment manages the root domain in domain
    /// mode
    DomainTaken { by: Vec<Option<String>> },
    /// other deployments use the root domain in the other mode: subdomain requested on the root
    /// domain of a domain deployment (`used` domain), or domain requested on the root domain of
    /// subdomain deployments (`used` subdomain)
    MixedModes {
        used: DnsMode,
        by: Vec<Option<String>>,
    },
}

impl RootDomain {
    /// the root domain of a zone listing: the dns modes on the zone and its rrsets, each
    /// deployment once
    pub fn of(listing: &HetznerDnsZoneListing) -> Self {
        let mut uses: Vec<DomainUse> = std::iter::once(&listing.zone.labels)
            .chain(listing.rrsets.iter().map(|rrset| &rrset.labels))
            .filter_map(|labels| {
                let dns_mode = DnsMode::parse(labels.get(DNS_MODE_LABEL)?)?;
                Some(DomainUse {
                    name: labels.get(NAME_LABEL).cloned(),
                    dns_mode,
                })
            })
            .collect();
        uses.sort();
        uses.dedup();
        RootDomain {
            name: listing.zone.name.clone(),
            uses,
        }
    }

    /// the deployments other than `name` that use the root domain in `dns_mode`
    fn others(&self, name: &str, dns_mode: DnsMode) -> Vec<Option<String>> {
        self.uses
            .iter()
            .filter(|found| found.dns_mode == dns_mode && found.name.as_deref() != Some(name))
            .map(|found| found.name.clone())
            .collect()
    }

    /// whether the deployment `name` can use the root domain in `dns_mode`. A root domain has
    /// one domain deployment or any number of subdomain deployments. The entries of the
    /// deployment itself do not count, so it can deploy again and change its mode
    pub fn check(
        &self,
        name: &str,
        dns_mode: DnsMode,
    ) -> std::result::Result<(), RootDomainConflict> {
        let domain = self.others(name, DnsMode::Domain);
        if !domain.is_empty() {
            return Err(match dns_mode {
                DnsMode::Domain => RootDomainConflict::DomainTaken { by: domain },
                DnsMode::Subdomain => RootDomainConflict::MixedModes {
                    used: DnsMode::Domain,
                    by: domain,
                },
            });
        }
        let subdomain = self.others(name, DnsMode::Subdomain);
        if dns_mode == DnsMode::Domain && !subdomain.is_empty() {
            return Err(RootDomainConflict::MixedModes {
                used: DnsMode::Subdomain,
                by: subdomain,
            });
        }
        Ok(())
    }

    /// the dns modes in which the deployment `name` can use the root domain; none when another
    /// deployment manages it in domain mode
    pub fn dns_modes(&self, name: &str) -> Vec<DnsMode> {
        DnsMode::ALL
            .into_iter()
            .filter(|mode| self.check(name, *mode).is_ok())
            .collect()
    }
}

/// the root domains of the project: every dns zone with the deployments that use it, sorted by
/// name
pub async fn root_domains(provider: &HetznerProviderRuntime) -> Result<Vec<RootDomain>> {
    Ok(provider
        .dns_zones()
        .list_all()
        .await?
        .iter()
        .map(RootDomain::of)
        .collect())
}

/// the base domain of a deployment: the root domain in domain mode, `<name>.<root domain>` in
/// subdomain mode
pub fn base_domain(name: &str, dns_mode: DnsMode, root_domain: &str) -> String {
    match dns_mode {
        DnsMode::Domain => root_domain.to_string(),
        DnsMode::Subdomain => format!("{name}.{root_domain}"),
    }
}

/// the host of the frontend of a deployment, `sak.<base domain>`, as the backend derives it
/// (`SakConfig.frontendHost`); the api is at `api.<base domain>`
pub fn frontend_host(name: &str, dns_mode: DnsMode, root_domain: &str) -> String {
    format!("sak.{}", base_domain(name, dns_mode, root_domain))
}

/// how sak sets up dns for a deployment: `domain` manages `<root domain>` and
/// `*.<root domain>`, `subdomain` (the default) manages `<name>.<root domain>` and
/// `*.<name>.<root domain>`. A root domain has one domain deployment or any number of
/// subdomain deployments (docs/development/fdr/FDR-001-dns-mode.md)
#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum DnsMode {
    Domain,
    #[default]
    Subdomain,
}

impl DnsMode {
    pub const ALL: [DnsMode; 2] = [DnsMode::Domain, DnsMode::Subdomain];

    /// the value in the label and on the command line, `domain` or `subdomain`
    pub fn as_str(self) -> &'static str {
        match self {
            DnsMode::Domain => "domain",
            DnsMode::Subdomain => "subdomain",
        }
    }

    pub fn parse(value: &str) -> Option<DnsMode> {
        DnsMode::ALL.into_iter().find(|mode| mode.as_str() == value)
    }
}

impl fmt::Display for DnsMode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::Labels;
    use crate::hetzner::api::{Location, ServerType};
    use crate::hetzner::deployment::fixtures::*;
    use crate::hetzner::deployment::*;
    use crate::hetzner::dns_zone::HetznerDnsZoneListing;
    use crate::hetzner::{DNS_MODE_LABEL, NAME_LABEL, ROOT_DOMAIN_LABEL};
    use crate::providers::hetzner::HetznerProviderRuntime;
    use serde_json::{Value, json};

    /// the labels of an entry: the dns mode and, when given, the name
    fn entry(name: Option<&str>, mode: &str) -> Labels {
        let mut labels = Labels::from([(DNS_MODE_LABEL.to_string(), mode.to_string())]);
        if let Some(name) = name {
            labels.insert(NAME_LABEL.to_string(), name.to_string());
        }
        labels
    }

    /// the root domain `pelle.io` with the labels of its zone and of its rrsets
    fn domain(zone_labels: Labels, rrset_labels: Vec<Labels>) -> RootDomain {
        RootDomain::of(&HetznerDnsZoneListing {
            zone: crate::hetzner::dns_zone::HetznerDnsZoneRuntime {
                id: 1,
                name: "pelle.io".to_string(),
                ttl: 3600,
                status: crate::hetzner::api::zones::ZoneStatus::Ok,
                nameservers: vec![],
                delete_protected: false,
                labels: zone_labels,
            },
            rrsets: rrset_labels
                .iter()
                .enumerate()
                .map(|(index, labels)| {
                    serde_json::from_value(crate::hetzner::dns_zone::provisioner_tests::rrset(
                        1,
                        &format!("r{index}"),
                        serde_json::to_value(labels).unwrap(),
                    ))
                    .unwrap()
                })
                .collect(),
        })
    }

    #[test]
    fn the_dns_mode_is_its_label_value_in_json_and_parsed() {
        assert_eq!(DnsMode::parse("subdomain"), Some(DnsMode::Subdomain));
        assert_eq!(DnsMode::parse("whole"), None);
        assert_eq!(
            serde_json::to_value(DnsMode::Domain).unwrap(),
            json!("domain")
        );
    }

    #[test]
    fn a_root_domain_nobody_uses_takes_both_modes() {
        let free = domain(Labels::new(), vec![Labels::new()]);
        assert!(free.uses.is_empty());
        assert_eq!(free.check("alpha", DnsMode::Domain), Ok(()));
        assert_eq!(free.check("alpha", DnsMode::Subdomain), Ok(()));
        assert_eq!(
            free.dns_modes("alpha"),
            vec![DnsMode::Domain, DnsMode::Subdomain]
        );
    }

    #[test]
    fn a_root_domain_of_a_domain_deployment_takes_no_other_deployment() {
        let taken = domain(Labels::new(), vec![entry(Some("beta"), "domain")]);
        let by = vec![Some("beta".to_string())];
        assert_eq!(
            taken.check("alpha", DnsMode::Domain),
            Err(RootDomainConflict::DomainTaken { by: by.clone() })
        );
        assert_eq!(
            taken.check("alpha", DnsMode::Subdomain),
            Err(RootDomainConflict::MixedModes {
                used: DnsMode::Domain,
                by
            })
        );
        assert!(taken.dns_modes("alpha").is_empty());
    }

    #[test]
    fn a_root_domain_with_subdomain_deployments_takes_only_subdomains() {
        let shared = domain(
            Labels::new(),
            vec![
                entry(Some("beta"), "subdomain"),
                entry(Some("gamma"), "subdomain"),
            ],
        );
        assert_eq!(
            shared.check("alpha", DnsMode::Domain),
            Err(RootDomainConflict::MixedModes {
                used: DnsMode::Subdomain,
                by: vec![Some("beta".to_string()), Some("gamma".to_string())]
            })
        );
        assert_eq!(shared.check("alpha", DnsMode::Subdomain), Ok(()));
        assert_eq!(shared.dns_modes("alpha"), vec![DnsMode::Subdomain]);
    }

    #[test]
    fn the_entries_of_the_deployment_itself_do_not_count() {
        // a deploy again in domain mode, or a change to subdomain
        let own_domain = domain(Labels::new(), vec![entry(Some("alpha"), "domain")]);
        assert_eq!(
            own_domain.dns_modes("alpha"),
            vec![DnsMode::Domain, DnsMode::Subdomain]
        );
        // the only subdomain deployment can change to domain
        let own_subdomain = domain(Labels::new(), vec![entry(Some("alpha"), "subdomain")]);
        assert_eq!(own_subdomain.check("alpha", DnsMode::Domain), Ok(()));
        // with another subdomain deployment on the root domain, it cannot
        let shared = domain(
            Labels::new(),
            vec![
                entry(Some("alpha"), "subdomain"),
                entry(Some("beta"), "subdomain"),
            ],
        );
        assert_eq!(shared.dns_modes("alpha"), vec![DnsMode::Subdomain]);
    }

    #[test]
    fn the_zone_and_entries_without_a_name_count_as_well() {
        let zone_labeled = domain(entry(Some("beta"), "domain"), vec![]);
        assert!(zone_labeled.dns_modes("alpha").is_empty());

        let unnamed = domain(Labels::new(), vec![entry(None, "domain")]);
        assert_eq!(
            unnamed.check("alpha", DnsMode::Subdomain),
            Err(RootDomainConflict::MixedModes {
                used: DnsMode::Domain,
                by: vec![None]
            })
        );
    }

    #[test]
    fn entries_without_a_known_dns_mode_are_left_out_and_each_deployment_counts_once() {
        let mut name_only = Labels::new();
        name_only.insert(NAME_LABEL.to_string(), "beta".to_string());
        let found = domain(
            Labels::new(),
            vec![
                name_only,
                entry(Some("gamma"), "whole"),
                entry(Some("delta"), "subdomain"),
                entry(Some("delta"), "subdomain"),
            ],
        );
        assert_eq!(
            found.uses,
            vec![DomainUse {
                name: Some("delta".to_string()),
                dns_mode: DnsMode::Subdomain
            }]
        );
    }

    #[tokio::test]
    async fn the_root_domains_are_the_zones_of_the_project() {
        use crate::hetzner::dns_zone::provisioner_tests::{mock_zones, rrset, zone};
        let (_server, api) = mock_zones(&[
            (
                zone(1, "pelle.io", json!({})),
                vec![rrset(
                    1,
                    "beta",
                    json!({ NAME_LABEL: "beta", DNS_MODE_LABEL: "subdomain" }),
                )],
            ),
            (zone(2, "example.com", json!({})), vec![]),
        ])
        .await;
        let provider = HetznerProviderRuntime::from_api(api, Location::hel1, ServerType::cx23);

        let domains = root_domains(&provider).await.unwrap();

        let names: Vec<&str> = domains.iter().map(|domain| domain.name.as_str()).collect();
        assert_eq!(names, vec!["example.com", "pelle.io"]);
        assert!(domains[0].uses.is_empty());
        assert_eq!(domains[1].dns_modes("alpha"), vec![DnsMode::Subdomain]);
    }

    #[test]
    fn the_frontend_is_sak_below_the_base_domain_of_the_dns_mode() {
        assert_eq!(
            frontend_host("alpha", DnsMode::Domain, "pelle.io"),
            "sak.pelle.io"
        );
        assert_eq!(
            frontend_host("alpha", DnsMode::Subdomain, "pelle.io"),
            "sak.alpha.pelle.io"
        );

        let mut deployment = DiscoveredDeployment::new("alpha");
        assert_eq!(deployment.frontend_url(), None);
        let key = |labels: Value| -> crate::hetzner::api::ssh_keys::SshKey {
            serde_json::from_value(key(7, "alpha", labels)).unwrap()
        };
        // without a dns mode on the resources, the deployment uses subdomain
        deployment.ssh_key =
            Some(key(json!({ NAME_LABEL: "alpha", ROOT_DOMAIN_LABEL: "pelle.io" })).into());
        assert_eq!(
            deployment.frontend_url().as_deref(),
            Some("https://sak.alpha.pelle.io")
        );
        deployment.ssh_key = Some(
            key(json!({ NAME_LABEL: "alpha", ROOT_DOMAIN_LABEL: "pelle.io", DNS_MODE_LABEL: "domain" }))
                .into(),
        );
        assert_eq!(
            deployment.frontend_url().as_deref(),
            Some("https://sak.pelle.io")
        );
    }
}
