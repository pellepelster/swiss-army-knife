//! the dns records of a deployment in the zones of the project: its own, those in its way and
//! those left over

use crate::api::{Labels, Lookup, Resource, Result};
use crate::hetzner::NAME_LABEL;
use crate::hetzner::api::zones::RRType;
use crate::hetzner::deployment::DeploymentSpec;
use crate::hetzner::dns_record::{HetznerDnsRecord, HetznerDnsRecordLookup};
use crate::hetzner::dns_zone::{HetznerDnsZoneListing, HetznerDnsZoneLookup};
use crate::providers::hetzner::HetznerProviderRuntime;

/// a dns record of a deployment, found by its name label in a zone of the project
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeploymentRecord {
    pub lookup: HetznerDnsRecordLookup,
    pub labels: Labels,
}

/// the dns records with the name label `name` in the zones of the project (`list_all` of the
/// dns zones). The deployment can remove only these records
pub fn deployment_records(listings: &[HetznerDnsZoneListing], name: &str) -> Vec<DeploymentRecord> {
    let mut records = Vec::new();
    for listing in listings {
        let zone = HetznerDnsZoneLookup::new(&listing.zone.name);
        for rrset in &listing.rrsets {
            if rrset.labels.get(NAME_LABEL).map(String::as_str) == Some(name) {
                records.push(DeploymentRecord {
                    lookup: HetznerDnsRecordLookup::new(&rrset.name, zone.clone(), rrset.rr_type),
                    labels: rrset.labels.clone(),
                });
            }
        }
    }
    records
}

/// the records that block the records of `spec`, as log texts: each rrset of any type at a name
/// that the deployment manages and without a label of its identity. So a name of another owner
/// stops the plan before anything is created. At the zone apex (`@`, domain mode) only A, AAAA
/// and CNAME block; the others there (SOA, NS, MX, TXT, CAA, …) belong to the zone and stay
pub fn foreign_records(listings: &[HetznerDnsZoneListing], spec: &DeploymentSpec) -> Vec<String> {
    let Some(root_domain) = &spec.root_domain else {
        return Vec::new();
    };
    let names = spec.record_names();
    let identity = spec.identity();
    let owned = |labels: &Labels| {
        identity
            .iter()
            .all(|(key, value)| labels.get(key) == Some(value))
    };
    listings
        .iter()
        .filter(|listing| &listing.zone.name == root_domain)
        .flat_map(|listing| &listing.rrsets)
        .filter(|rrset| names.contains(&rrset.name) && !owned(&rrset.labels))
        .filter(|rrset| {
            rrset.name != "@" || matches!(rrset.rr_type, RRType::A | RRType::AAAA | RRType::CNAME)
        })
        .map(|rrset| {
            HetznerDnsRecordLookup::new(
                &rrset.name,
                HetznerDnsZoneLookup::new(root_domain),
                rrset.rr_type,
            )
            .log_text()
        })
        .collect()
}

/// the records of `found` that are not in `declared`: all of them on a destroy, those of the
/// old dns mode or root domain on a deploy. Only records with every label of `identity` (the
/// name and the password hash) are included
pub fn stale_records(
    found: &[DeploymentRecord],
    declared: &[HetznerDnsRecord],
    identity: &Labels,
) -> Vec<HetznerDnsRecordLookup> {
    found
        .iter()
        .filter(|record| {
            identity
                .iter()
                .all(|(key, value)| record.labels.get(key) == Some(value))
        })
        .map(|record| record.lookup.clone())
        .filter(|lookup| {
            !declared
                .iter()
                .any(|record| Lookup::name(&record.as_lookup()) == Lookup::name(lookup))
        })
        .collect()
}

/// removes the records and reports each to `on_removed`, with `false` when it was gone
pub async fn remove_records(
    provider: &HetznerProviderRuntime,
    records: &[HetznerDnsRecordLookup],
    on_removed: &mut (dyn FnMut(&HetznerDnsRecordLookup, bool) + Send),
) -> Result<()> {
    for record in records {
        let rrsets = provider.api.zones().rrsets(&record.zone.name);
        let gone = match rrsets.delete(&record.record, record.rr_type).await? {
            Some(action) => {
                rrsets.wait_for_action(&action).await?;
                true
            }
            None => false,
        };
        on_removed(record, gone);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::{Labels, Lookup};
    use crate::hetzner::api::zones::RRType;
    use crate::hetzner::api::{Location, ServerType};
    use crate::hetzner::deployment::fixtures::*;
    use crate::hetzner::deployment::*;
    use crate::hetzner::dns_record::HetznerDnsRecordLookup;
    use crate::hetzner::dns_zone::{HetznerDnsZoneListing, HetznerDnsZoneLookup};
    use crate::hetzner::{NAME_LABEL, PASSWORD_HASH_LABEL};
    use crate::providers::hetzner::HetznerProviderRuntime;
    use serde_json::json;

    /// the zone pelle.io with rrsets of the given name, type and labels
    fn zone_with(rrsets: &[(&str, &str, Labels)]) -> Vec<HetznerDnsZoneListing> {
        vec![HetznerDnsZoneListing {
            zone: crate::hetzner::dns_zone::HetznerDnsZoneRuntime {
                id: 1,
                name: "pelle.io".to_string(),
                ttl: 3600,
                status: crate::hetzner::api::zones::ZoneStatus::Ok,
                nameservers: vec![],
                delete_protected: false,
                labels: Labels::new(),
            },
            rrsets: rrsets
                .iter()
                .map(|(name, rr_type, labels)| {
                    serde_json::from_value(json!({
                        "id": format!("{name}/{rr_type}"), "name": name, "type": rr_type, "ttl": null,
                        "protection": { "change": false }, "records": [{ "value": "x" }], "zone": 1,
                        "labels": labels
                    }))
                    .unwrap()
                })
                .collect(),
        }]
    }

    #[test]
    fn a_record_of_any_type_at_a_managed_name_is_in_the_way() {
        let subdomain = DeploymentSpec::new("www", "k", Location::hel1, ServerType::cx23)
            .unwrap()
            .with_dns_mode(DnsMode::Subdomain)
            .with_root_domain("pelle.io")
            .with_password_hash("abc");
        // a record of another owner at a name of the deployment stops the plan, of any type
        for rr_type in ["CNAME", "MX", "TXT", "A"] {
            assert_eq!(
                foreign_records(&zone_with(&[("www", rr_type, Labels::new())]), &subdomain),
                vec![format!("dns record 'www.pelle.io/{rr_type}'")],
                "{rr_type}"
            );
        }
        assert_eq!(
            foreign_records(&zone_with(&[("*.www", "TXT", Labels::new())]), &subdomain).len(),
            1
        );
        // the records of the deployment itself and the names it does not manage do not block
        let own = subdomain.identity();
        assert!(
            foreign_records(
                &zone_with(&[("www", "A", own.clone()), ("mail", "MX", Labels::new())]),
                &subdomain
            )
            .is_empty()
        );
        // a record with the name of the deployment but another password hash is not its own
        let mut other = own.clone();
        other.insert(PASSWORD_HASH_LABEL.to_string(), "xyz".to_string());
        assert_eq!(
            foreign_records(&zone_with(&[("www", "AAAA", other)]), &subdomain).len(),
            1
        );

        // domain mode: the zone keeps its own records at the apex; a CNAME or an address record
        // of another owner there stops the plan
        let domain = subdomain.clone().with_dns_mode(DnsMode::Domain);
        let apex = zone_with(&[
            ("@", "SOA", Labels::new()),
            ("@", "NS", Labels::new()),
            ("@", "MX", Labels::new()),
            ("@", "TXT", Labels::new()),
        ]);
        assert!(foreign_records(&apex, &domain).is_empty());
        assert_eq!(
            foreign_records(&zone_with(&[("@", "A", Labels::new())]), &domain),
            vec!["dns record '@.pelle.io/A'".to_string()]
        );
        assert_eq!(
            foreign_records(&zone_with(&[("*", "MX", Labels::new())]), &domain).len(),
            1
        );
    }

    #[test]
    fn the_records_of_another_mode_or_domain_are_stale_when_they_carry_the_identity() {
        let identity = Labels::from([
            (NAME_LABEL.to_string(), "alpha".to_string()),
            (PASSWORD_HASH_LABEL.to_string(), "abc".to_string()),
        ]);
        let record = |name: &str, zone: &str, labels: &Labels| DeploymentRecord {
            lookup: HetznerDnsRecordLookup::new(name, HetznerDnsZoneLookup::new(zone), RRType::A),
            labels: labels.clone(),
        };
        let mut other_password = identity.clone();
        other_password.insert(PASSWORD_HASH_LABEL.to_string(), "xyz".to_string());
        let found = vec![
            record("@", "pelle.io", &identity),
            record("alpha", "pelle.io", &identity),
            record("alpha", "example.com", &identity),
            // only the name label, or another password hash: the deployment does not remove these
            record(
                "www",
                "pelle.io",
                &Labels::from([(NAME_LABEL.to_string(), "alpha".to_string())]),
            ),
            record("old", "pelle.io", &other_password),
        ];
        let declared = spec(DnsMode::Subdomain)
            .with_password_hash("abc")
            .dns_records();

        let stale = stale_records(&found, &declared, &identity);

        assert_eq!(
            stale,
            vec![found[0].lookup.clone(), found[2].lookup.clone()]
        );
        // a destroy declares no records: every record with the identity is stale
        assert_eq!(stale_records(&found, &[], &identity).len(), 3);
    }

    #[tokio::test]
    async fn the_records_of_a_deployment_are_those_with_its_name_label() {
        use crate::hetzner::dns_zone::provisioner_tests::{mock_zones, rrset, zone};
        let (_server, api) = mock_zones(&[
            (
                zone(1, "pelle.io", json!({})),
                vec![
                    rrset(1, "alpha", json!({ NAME_LABEL: "alpha" })),
                    rrset(1, "beta", json!({ NAME_LABEL: "beta" })),
                    rrset(1, "www", json!({})),
                ],
            ),
            (
                zone(2, "example.com", json!({})),
                vec![rrset(2, "@", json!({ NAME_LABEL: "alpha" }))],
            ),
        ])
        .await;
        let provider = HetznerProviderRuntime::from_api(api);
        let listings = provider.dns_zones().list_all().await.unwrap();

        let records = deployment_records(&listings, "alpha");

        let names: Vec<String> = records
            .iter()
            .map(|record| record.lookup.log_text())
            .collect();
        assert_eq!(
            names,
            vec![
                "dns record '@.example.com/A'",
                "dns record 'alpha.pelle.io/A'"
            ]
        );
    }
}
