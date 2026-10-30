use crate::api::{DiffItem, Error, Resource, Result};
use crate::hetzner::api::ssh_keys::{SshKeyCreate, SshKeyUpdate};
use crate::hetzner::api::{HetznerApi, ListQuery};
use crate::hetzner::kind::HetznerKind;
use crate::hetzner::ssh_key::{HetznerSshKey, HetznerSshKeyRuntime};
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

/// the fingerprint of the declared key; `None` for a key declared without its public key (the
/// deployment it is declared for has none left, `DeploymentSpec::declared_by`): it is missing,
/// or compared by its labels only
fn expected_fingerprint(resource: &HetznerSshKey) -> Result<Option<String>> {
    if resource.public_key.is_empty() {
        Ok(None)
    } else {
        fingerprint(&resource.public_key).map(Some)
    }
}

pub struct HetznerSshKeyProvisioner {
    api: Arc<HetznerApi>,
}

impl HetznerSshKeyProvisioner {
    pub fn new(api: Arc<HetznerApi>) -> Self {
        HetznerSshKeyProvisioner { api }
    }
}

#[async_trait]
impl HetznerKind for HetznerSshKeyProvisioner {
    type Resource = HetznerSshKey;
    type Runtime = HetznerSshKeyRuntime;

    async fn fetch(&self, name: &str) -> Result<Option<HetznerSshKeyRuntime>> {
        Ok(self.api.ssh_keys().get_by_name(name).await?.map(Into::into))
    }

    async fn names(&self) -> Result<Vec<String>> {
        let keys = self.api.ssh_keys().list(&ListQuery::new()).await?;
        Ok(keys.into_iter().map(|key| key.name).collect())
    }

    fn compare(
        &self,
        resource: &HetznerSshKey,
        runtime: &HetznerSshKeyRuntime,
    ) -> Result<Vec<DiffItem>> {
        let mut changes = Vec::new();
        if let Some(expected) = expected_fingerprint(resource)?
            && runtime.fingerprint != expected
        {
            changes.push(DiffItem::recreate(
                "fingerprint",
                &expected,
                &runtime.fingerprint,
            ));
        }
        Ok(changes)
    }

    /// hetzner refuses a second key with the same fingerprint, also under another name
    async fn duplicate_of(&self, resource: &HetznerSshKey) -> Result<Option<String>> {
        let Some(expected) = expected_fingerprint(resource)? else {
            return Ok(None);
        };
        let duplicate = self
            .api
            .ssh_keys()
            .list(&ListQuery::new())
            .await?
            .into_iter()
            .find(|key| key.fingerprint == expected);
        Ok(duplicate.map(|key| {
            format!(
                "another key with the fingerprint '{}' already exists ({})",
                key.fingerprint, key.name
            )
        }))
    }

    async fn create(&self, resource: &HetznerSshKey) -> Result<HetznerSshKeyRuntime> {
        info!("{} not found, creating", resource.log_text());
        let request = SshKeyCreate {
            name: resource.name.clone(),
            public_key: resource.public_key.clone(),
            labels: Some(resource.labels.clone()),
        };
        Ok(self.api.ssh_keys().create(&request).await?.into())
    }

    async fn update_labels(
        &self,
        resource: &HetznerSshKey,
        runtime: &HetznerSshKeyRuntime,
    ) -> Result<()> {
        let request = SshKeyUpdate {
            name: Some(resource.name.clone()),
            labels: Some(resource.labels.clone()),
        };
        self.api.ssh_keys().update(runtime.id, &request).await?;
        Ok(())
    }

    async fn delete(&self, runtime: &HetznerSshKeyRuntime) -> Result<()> {
        self.api.ssh_keys().delete(runtime.id).await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::{DiffStatus, LookupProviders, Provisioner};
    use crate::hetzner::test_support::{labels, mock_api};
    use serde_json::{Value, json};

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

    async fn provisioner_with(
        keys: Vec<Value>,
    ) -> (wiremock::MockServer, HetznerSshKeyProvisioner) {
        let (server, api) = mock_api("ssh_keys", keys).await;
        (server, HetznerSshKeyProvisioner::new(api))
    }

    #[tokio::test]
    async fn diff_of_a_missing_key() {
        let (_server, provisioner) = provisioner_with(vec![]).await;

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
            provisioner_with(vec![key("admin", FINGERPRINT, json!({ "app": "sak" }))]).await;
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
            provisioner_with(vec![key("admin", "aa:bb", json!({ "app": "old" }))]).await;
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
        let (_server, provisioner) =
            provisioner_with(vec![key("old-name", FINGERPRINT, json!({}))]).await;

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
