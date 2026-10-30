use crate::api::identity;
use crate::api::{
    ApplyContext, DestroyContext, DiffContext, DiffItem, Error, Lookup, LookupContext,
    LookupContextExt, LookupProvider, Provisioner, Resource, ResourceDiff, Result,
};
use crate::hetzner::api::HetznerApi;
use crate::hetzner::api::primary_ips::PrimaryIpType;
use crate::hetzner::api::zones::{RRSetCreate, RRSetUpdate, Record};
use crate::hetzner::dns_record::{
    HetznerDnsRecord, HetznerDnsRecordLookup, HetznerDnsRecordRuntime, RecordValue,
};
use crate::hetzner::hetzner_label_diff;
use async_trait::async_trait;
use std::collections::HashSet;
use std::sync::Arc;
use tracing::info;

pub struct HetznerDnsRecordProvisioner {
    api: Arc<HetznerApi>,
}

impl HetznerDnsRecordProvisioner {
    pub fn new(api: Arc<HetznerApi>) -> Self {
        HetznerDnsRecordProvisioner { api }
    }

    /// the record set of the lookup; `NotOwned` when it lacks an identity label of the lookup
    async fn find(
        &self,
        lookup: &HetznerDnsRecordLookup,
    ) -> Result<Option<HetznerDnsRecordRuntime>> {
        let found = self
            .api
            .zones()
            .rrsets(&lookup.zone.name)
            .get(&lookup.record, lookup.rr_type)
            .await?
            .map(|rrset| HetznerDnsRecordRuntime::from_rrset(rrset, &lookup.zone.name));
        identity::check_lookup(found, lookup)
    }

    /// the declared values of the records, resolved through the context
    async fn values(
        &self,
        resource: &HetznerDnsRecord,
        context: &dyn LookupContext,
    ) -> Result<Vec<String>> {
        let mut values = Vec::new();
        for value in &resource.values {
            values.push(match value {
                RecordValue::Literal(value) => value.clone(),
                RecordValue::ServerIpv4(server) => context
                    .lookup(server)
                    .await?
                    .ok_or_else(|| Error::NotFound(server.log_text()))?
                    .public_ipv4
                    .ok_or_else(|| {
                        Error::message(format!("{} has no public ipv4", server.log_text()))
                    })?,
                RecordValue::PrimaryIp(ip) => {
                    let ip = context
                        .lookup(ip)
                        .await?
                        .ok_or_else(|| Error::NotFound(ip.log_text()))?;
                    match ip.ip_type {
                        PrimaryIpType::Ipv4 => ip.ip,
                        PrimaryIpType::Ipv6 => first_address(&ip.ip),
                    }
                }
            });
        }
        Ok(values)
    }
}

/// `2001:db8::/64` -> `2001:db8::1`: the address that hetzner gives a server in its ipv6
/// network. The AAAA records point to it and the backend gets it
pub fn first_address(network: &str) -> String {
    let prefix = network.split('/').next().unwrap_or(network);
    match prefix.strip_suffix("::") {
        Some(head) => format!("{head}::1"),
        None => prefix.to_string(),
    }
}

fn same_values(a: &[String], b: &[String]) -> bool {
    a.iter().collect::<HashSet<_>>() == b.iter().collect::<HashSet<_>>()
}

#[async_trait]
impl LookupProvider<HetznerDnsRecordLookup> for HetznerDnsRecordProvisioner {
    async fn lookup(
        &self,
        lookup: &HetznerDnsRecordLookup,
        _: &dyn LookupContext,
    ) -> Result<Option<HetznerDnsRecordRuntime>> {
        self.find(lookup).await
    }
}

#[async_trait]
impl Provisioner<HetznerDnsRecord> for HetznerDnsRecordProvisioner {
    async fn diff(
        &self,
        resource: &HetznerDnsRecord,
        context: &dyn DiffContext,
    ) -> Result<ResourceDiff> {
        if context.lookup(&resource.zone).await?.is_none() {
            return Ok(ResourceDiff::parent_missing(resource));
        }
        let Some(runtime) = self.find(&resource.as_lookup()).await? else {
            return Ok(ResourceDiff::missing(resource));
        };

        let mut changes = Vec::new();
        if let Some(ttl) = resource.ttl
            && Some(ttl) != runtime.ttl
        {
            changes.push(DiffItem::changed("ttl", ttl, "").with_actual(runtime.ttl));
        }

        let pending = resource
            .values
            .iter()
            .filter_map(|value| match value {
                RecordValue::Literal(_) => None,
                RecordValue::ServerIpv4(server) => Some(server.resource_ref()),
                RecordValue::PrimaryIp(ip) => Some(ip.resource_ref()),
            })
            .any(|dependency| context.has_pending_change(&dependency));
        if pending {
            changes.push(DiffItem::changed(
                "value",
                "<known after apply>",
                runtime.values.join(", "),
            ));
        } else {
            let expected = self.values(resource, context).await?;
            if !same_values(&expected, &runtime.values) {
                changes.push(DiffItem::changed(
                    "value",
                    expected.join(", "),
                    runtime.values.join(", "),
                ));
            }
        }

        changes.extend(hetzner_label_diff(resource, &runtime));

        Ok(ResourceDiff::from_changes(resource, changes))
    }

    async fn apply(
        &self,
        resource: &HetznerDnsRecord,
        context: &dyn ApplyContext,
    ) -> Result<HetznerDnsRecordRuntime> {
        context
            .lookup(&resource.zone)
            .await?
            .ok_or_else(|| Error::NotFound(resource.zone.log_text()))?;
        let values = self.values(resource, context).await?;
        let records: Vec<Record> = values.iter().map(|value| Record::new(value)).collect();
        let rrsets = self.api.zones().rrsets(&resource.zone.name);
        let lookup = resource.as_lookup();

        match self.find(&lookup).await? {
            Some(runtime) => {
                if let Some(ttl) = resource.ttl
                    && Some(ttl) != runtime.ttl
                {
                    let action = rrsets
                        .change_ttl(&resource.name, resource.rr_type, ttl)
                        .await?;
                    rrsets.wait_for_action(&action).await?;
                }
                if !same_values(&values, &runtime.values) {
                    info!("setting {} to {}", resource.log_text(), values.join(", "));
                    let action = rrsets
                        .set_records(&resource.name, resource.rr_type, &records)
                        .await?;
                    rrsets.wait_for_action(&action).await?;
                }
                if runtime.labels != resource.labels {
                    let request = RRSetUpdate {
                        labels: Some(resource.labels.clone()),
                    };
                    rrsets
                        .update(&resource.name, resource.rr_type, &request)
                        .await?;
                }
            }
            None => {
                info!(
                    "{} not found, creating with {}",
                    resource.log_text(),
                    values.join(", ")
                );
                let request = RRSetCreate {
                    name: resource.name.clone(),
                    rr_type: resource.rr_type,
                    ttl: resource.ttl,
                    records,
                    labels: Some(resource.labels.clone()),
                };
                let created = rrsets.create(&request).await?;
                if let Some(action) = &created.action {
                    rrsets.wait_for_action(action).await?;
                }
            }
        }

        self.find(&lookup)
            .await?
            .ok_or_else(|| Error::message(format!("{} is gone after apply", resource.log_text())))
    }

    async fn destroy(
        &self,
        runtime: &HetznerDnsRecordRuntime,
        _: &dyn DestroyContext,
    ) -> Result<()> {
        let rrsets = self.api.zones().rrsets(&runtime.zone);
        if let Some(action) = rrsets.delete(&runtime.name, runtime.rr_type).await? {
            rrsets.wait_for_action(&action).await?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::{DiffStatus, LookupProviders};
    use crate::hetzner::NAME_LABEL;
    use crate::hetzner::api::Backoff;
    use crate::hetzner::dns_zone::{HetznerDnsZoneLookup, HetznerDnsZoneProvisioner};
    use crate::hetzner::test_support::labels;
    use serde_json::{Value, json};
    use std::time::Duration;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn rrset(values: &[&str], ttl: Option<u64>) -> Value {
        let records: Vec<Value> = values
            .iter()
            .map(|value| json!({ "value": value, "comment": "" }))
            .collect();
        json!({ "id": "www/A", "name": "www", "type": "A", "ttl": ttl, "labels": { "app": "sak" }, "protection": { "change": false }, "records": records, "zone": 1 })
    }

    /// a zone `example.de` with the rrset `www/A`, when given
    async fn mock(rrset: Option<Value>) -> (MockServer, Arc<HetznerApi>) {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/zones/example.de"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "zone": {
                "id": 1, "name": "example.de", "mode": "primary", "ttl": 3600, "protection": { "delete": false }, "status": "ok", "labels": {}
            } })))
            .mount(&server)
            .await;
        let response = match rrset {
            Some(rrset) => ResponseTemplate::new(200).set_body_json(json!({ "rrset": rrset })),
            None => ResponseTemplate::new(404)
                .set_body_json(json!({ "error": { "code": "not_found", "message": "no" } })),
        };
        Mock::given(method("GET"))
            .and(path("/zones/example.de/rrsets/www/A"))
            .respond_with(response)
            .mount(&server)
            .await;
        let api = HetznerApi::with_base_url("token", &server.uri()).with_retry(Backoff::new(
            1,
            Duration::ZERO,
            Duration::ZERO,
        ));
        (server, Arc::new(api))
    }

    fn context(api: &Arc<HetznerApi>) -> LookupProviders {
        LookupProviders::new()
            .with::<HetznerDnsZoneLookup, _>(Arc::new(HetznerDnsZoneProvisioner::new(api.clone())))
    }

    fn resource(values: &[&str]) -> HetznerDnsRecord {
        let values = values
            .iter()
            .map(|value| RecordValue::literal(value))
            .collect();
        HetznerDnsRecord::a("www", HetznerDnsZoneLookup::new("example.de"), values)
            .with_labels(labels(&[("app", "sak")]))
    }

    #[tokio::test]
    async fn diff_of_a_missing_record_and_of_a_missing_zone() {
        let (_server, api) = mock(None).await;
        let provisioner = HetznerDnsRecordProvisioner::new(api.clone());

        let diff = provisioner
            .diff(&resource(&["203.0.113.1"]), &context(&api))
            .await
            .unwrap();
        assert_eq!(diff.status, DiffStatus::Missing);

        let other_zone = HetznerDnsRecord {
            zone: HetznerDnsZoneLookup::new("other.de"),
            ..resource(&[])
        };
        let diff = provisioner.diff(&other_zone, &context(&api)).await.unwrap();
        assert_eq!(diff.status, DiffStatus::ParentMissing);
    }

    #[tokio::test]
    async fn a_record_of_the_user_or_another_deployment_is_never_taken_over() {
        // the rrset of the mock has no name label: it belongs to the user
        let (_server, api) = mock(Some(rrset(&["203.0.113.1"], None))).await;
        let provisioner = HetznerDnsRecordProvisioner::new(api.clone());
        let ours = resource(&["203.0.113.9"]).with_identity(labels(&[(NAME_LABEL, "alpha")]));

        // the lookup, the diff and the apply all refuse it; the mock has no route for a
        // change, so nothing was written
        for error in [
            provisioner
                .lookup(&ours.as_lookup(), &context(&api))
                .await
                .err(),
            provisioner.diff(&ours, &context(&api)).await.err(),
            provisioner.apply(&ours, &context(&api)).await.err(),
        ] {
            assert!(
                matches!(&error, Some(Error::NotOwned { resource, reason })
                    if resource == "dns record 'www.example.de/A'" && reason == "it has no label 'sak.pelle.io/name'"),
                "{error:?}"
            );
        }

        let mut theirs = rrset(&["203.0.113.1"], None);
        theirs["labels"] = json!({ NAME_LABEL: "beta" });
        let (_server, api) = mock(Some(theirs)).await;
        let provisioner = HetznerDnsRecordProvisioner::new(api.clone());
        assert!(matches!(
            provisioner.apply(&ours, &context(&api)).await,
            Err(Error::NotOwned { .. })
        ));
        // without an identity, the lookup finds any record of the name
        assert!(
            provisioner
                .lookup(&resource(&[]).as_lookup(), &context(&api))
                .await
                .unwrap()
                .is_some()
        );
    }

    #[tokio::test]
    async fn values_compare_as_sets_and_the_ttl_only_when_declared() {
        let (_server, api) = mock(Some(rrset(&["203.0.113.1", "203.0.113.2"], Some(300)))).await;
        let provisioner = HetznerDnsRecordProvisioner::new(api.clone());

        let diff = provisioner
            .diff(&resource(&["203.0.113.2", "203.0.113.1"]), &context(&api))
            .await
            .unwrap();
        assert_eq!(diff.status, DiffStatus::UpToDate, "{:?}", diff.changes);

        let changed = resource(&["203.0.113.3"]).with_ttl(60);
        let diff = provisioner.diff(&changed, &context(&api)).await.unwrap();
        assert_eq!(
            diff.changes,
            vec![
                DiffItem::changed("ttl", "60", "300"),
                DiffItem::changed("value", "203.0.113.3", "203.0.113.1, 203.0.113.2"),
            ]
        );
    }

    #[test]
    fn the_first_address_of_an_ipv6_network() {
        assert_eq!(first_address("2001:db8:1234::/64"), "2001:db8:1234::1");
        assert_eq!(first_address("2001:db8::5"), "2001:db8::5");
    }

    #[test]
    fn the_lookup_name_carries_zone_and_type() {
        let record = resource(&[]);
        assert_eq!(record.as_lookup().name(), "www.example.de/A");
        assert_eq!(record.log_text(), "dns record 'www.example.de/A'");
        assert_eq!(record.depends_on().len(), 1);
    }
}
