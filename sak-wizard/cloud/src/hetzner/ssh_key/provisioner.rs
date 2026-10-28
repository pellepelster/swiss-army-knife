use crate::api::{
    ApplyContext, DestroyContext, DiffContext, DiffItem, Error, Listable, LookupContext,
    LookupProvider, Provisioner, Resource, ResourceDiff, Result,
};
use crate::hetzner::api::ssh_keys::{SshKeyCreate, SshKeyUpdate};
use crate::hetzner::api::{HetznerApi, ListQuery};
use crate::hetzner::hetzner_label_diff;
use crate::hetzner::ssh_key::{HetznerSshKey, HetznerSshKeyLookup, HetznerSshKeyRuntime};
use async_trait::async_trait;
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use md5::{Digest, Md5};
use std::sync::Arc;
use tracing::info;

/// the md5 fingerprint of a public key as hetzner computes it, `99:fc:9b:...`
pub fn fingerprint(public_key: &str) -> Result<String> {
    let blob = public_key.split_whitespace().nth(1).ok_or_else(|| {
        Error::message("invalid ssh public key, expected '<type> <base64 key> [comment]'")
    })?;
    let bytes = STANDARD.decode(blob).map_err(|error| {
        Error::message(format!(
            "invalid ssh public key, the key is no base64: {error}"
        ))
    })?;
    let digest = Md5::digest(&bytes);
    Ok(digest
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<Vec<_>>()
        .join(":"))
}

pub struct HetznerSshKeyProvisioner {
    api: Arc<HetznerApi>,
}

impl HetznerSshKeyProvisioner {
    pub fn new(api: Arc<HetznerApi>) -> Self {
        HetznerSshKeyProvisioner { api }
    }

    async fn find(&self, name: &str) -> Result<Option<HetznerSshKeyRuntime>> {
        Ok(self.api.ssh_keys().get_by_name(name).await?.map(Into::into))
    }
}

#[async_trait]
impl LookupProvider<HetznerSshKeyLookup> for HetznerSshKeyProvisioner {
    async fn lookup(
        &self,
        lookup: &HetznerSshKeyLookup,
        _: &dyn LookupContext,
    ) -> Result<Option<HetznerSshKeyRuntime>> {
        self.find(&lookup.name).await
    }
}

#[async_trait]
impl Listable<HetznerSshKeyLookup> for HetznerSshKeyProvisioner {
    async fn list(&self) -> Result<Vec<HetznerSshKeyLookup>> {
        Ok(self
            .api
            .ssh_keys()
            .list(&ListQuery::new())
            .await?
            .iter()
            .map(|key| HetznerSshKeyLookup::new(&key.name))
            .collect())
    }
}

#[async_trait]
impl Provisioner<HetznerSshKey> for HetznerSshKeyProvisioner {
    async fn diff(&self, resource: &HetznerSshKey, _: &dyn DiffContext) -> Result<ResourceDiff> {
        let expected = fingerprint(&resource.public_key)?;

        let Some(runtime) = self.find(&resource.name).await? else {
            // hetzner refuses a second key with the same fingerprint, whatever its name
            let duplicate = self
                .api
                .ssh_keys()
                .list(&ListQuery::new())
                .await?
                .into_iter()
                .find(|key| key.fingerprint == expected);
            return Ok(match duplicate {
                Some(key) => ResourceDiff::duplicate(
                    resource,
                    format!(
                        "another key with the fingerprint '{}' already exists ({})",
                        key.fingerprint, key.name
                    ),
                ),
                None => ResourceDiff::missing(resource),
            });
        };

        let mut changes = Vec::new();
        if runtime.fingerprint != expected {
            changes.push(DiffItem::recreate(
                "fingerprint",
                &expected,
                &runtime.fingerprint,
            ));
        }
        changes.extend(hetzner_label_diff(resource, &runtime));

        Ok(ResourceDiff::from_changes(resource, changes))
    }

    async fn apply(
        &self,
        resource: &HetznerSshKey,
        _: &dyn ApplyContext,
    ) -> Result<HetznerSshKeyRuntime> {
        let runtime = match self.find(&resource.name).await? {
            Some(runtime) => runtime,
            None => {
                info!("{} not found, creating", resource.log_text());
                let request = SshKeyCreate {
                    name: resource.name.clone(),
                    public_key: resource.public_key.clone(),
                    labels: Some(resource.labels.clone()),
                };
                self.api.ssh_keys().create(&request).await?.into()
            }
        };

        let request = SshKeyUpdate {
            name: Some(resource.name.clone()),
            labels: Some(resource.labels.clone()),
        };
        Ok(self
            .api
            .ssh_keys()
            .update(runtime.id, &request)
            .await?
            .into())
    }

    async fn destroy(&self, lookup: &HetznerSshKeyLookup, _: &dyn DestroyContext) -> Result<bool> {
        match self.find(&lookup.name).await? {
            Some(runtime) => Ok(self.api.ssh_keys().delete(runtime.id).await?),
            None => Ok(false),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::{DiffStatus, Labels, LookupProviders};
    use crate::hetzner::api::Backoff;
    use serde_json::{Value, json};
    use std::time::Duration;
    use wiremock::matchers::{method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    pub const PUBLIC_KEY: &str = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE+u0dEVRZDzzp4E1teCqF49r8ig3YEk8eaPqNWfDcPb pelle@fry";
    pub const FINGERPRINT: &str = "99:fc:9b:f4:04:69:2c:9d:30:d5:2c:d9:1e:ca:b2:76";

    #[test]
    fn fingerprint_is_the_md5_of_the_key_blob() {
        assert_eq!(fingerprint(PUBLIC_KEY).unwrap(), FINGERPRINT);
        assert_eq!(
            fingerprint("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDBQBtbY8Kdi4XtH2sTVSkepcdhZHWEnKAQ8PrPcIp/p pelle@fry").unwrap(),
            "d3:16:bf:af:b2:60:3e:e1:56:0d:54:3d:73:bb:9a:38"
        );
        assert!(fingerprint("ssh-ed25519").is_err());
        assert!(fingerprint("ssh-ed25519 not*base64").is_err());
    }

    fn key(name: &str, fingerprint: &str, labels: Value) -> Value {
        json!({ "id": 7, "name": name, "fingerprint": fingerprint, "public_key": PUBLIC_KEY, "labels": labels })
    }

    fn page(keys: Vec<Value>) -> Value {
        json!({ "ssh_keys": keys, "meta": { "pagination": { "page": 1, "per_page": 50, "next_page": null, "total_entries": keys.len() } } })
    }

    /// a mock listing `keys`, the name filter is honored
    async fn api_with(keys: Vec<Value>) -> (MockServer, HetznerSshKeyProvisioner) {
        let server = MockServer::start().await;
        for key in &keys {
            Mock::given(method("GET"))
                .and(path("/ssh_keys"))
                .and(query_param("name", key["name"].as_str().unwrap()))
                .respond_with(ResponseTemplate::new(200).set_body_json(page(vec![key.clone()])))
                .mount(&server)
                .await;
        }
        // any other name finds nothing, a list without a name filter everything
        let any_name =
            |request: &wiremock::Request| request.url.query_pairs().any(|(key, _)| key == "name");
        Mock::given(method("GET"))
            .and(path("/ssh_keys"))
            .and(any_name)
            .respond_with(ResponseTemplate::new(200).set_body_json(page(vec![])))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/ssh_keys"))
            .respond_with(ResponseTemplate::new(200).set_body_json(page(keys)))
            .mount(&server)
            .await;
        let api = HetznerApi::with_base_url("token", &server.uri()).with_retry(Backoff::new(
            1,
            Duration::ZERO,
            Duration::ZERO,
        ));
        (server, HetznerSshKeyProvisioner::new(Arc::new(api)))
    }

    fn labels(pairs: &[(&str, &str)]) -> Labels {
        pairs
            .iter()
            .map(|(key, value)| (key.to_string(), value.to_string()))
            .collect()
    }

    #[tokio::test]
    async fn diff_of_a_missing_key() {
        let (_server, provisioner) = api_with(vec![]).await;

        let diff = provisioner
            .diff(
                &HetznerSshKey::new("admin", PUBLIC_KEY),
                &LookupProviders::new(),
            )
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::Missing);
        assert!(diff.changes.is_empty());
    }

    #[tokio::test]
    async fn diff_of_an_up_to_date_key() {
        let (_server, provisioner) =
            api_with(vec![key("admin", FINGERPRINT, json!({ "app": "sak" }))]).await;
        let resource =
            HetznerSshKey::new("admin", PUBLIC_KEY).with_labels(labels(&[("app", "sak")]));

        let diff = provisioner
            .diff(&resource, &LookupProviders::new())
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::UpToDate);
    }

    #[tokio::test]
    async fn a_changed_key_needs_a_recreate_a_changed_label_does_not() {
        let (_server, provisioner) =
            api_with(vec![key("admin", "aa:bb", json!({ "app": "old" }))]).await;
        let resource =
            HetznerSshKey::new("admin", PUBLIC_KEY).with_labels(labels(&[("app", "sak")]));

        let diff = provisioner
            .diff(&resource, &LookupProviders::new())
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::HasChanges);
        assert!(diff.needs_recreate());
        assert_eq!(
            diff.changes,
            vec![
                DiffItem::recreate("fingerprint", FINGERPRINT, "aa:bb"),
                DiffItem::changed("label 'app'", "sak", "old")
            ]
        );
    }

    #[tokio::test]
    async fn the_same_key_under_another_name_is_a_duplicate() {
        let (_server, provisioner) = api_with(vec![key("old-name", FINGERPRINT, json!({}))]).await;

        let diff = provisioner
            .diff(
                &HetznerSshKey::new("admin", PUBLIC_KEY),
                &LookupProviders::new(),
            )
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::Duplicate);
        assert_eq!(
            diff.duplicate_message.as_deref(),
            Some(
                "another key with the fingerprint '99:fc:9b:f4:04:69:2c:9d:30:d5:2c:d9:1e:ca:b2:76' already exists (old-name)"
            )
        );
    }
}
