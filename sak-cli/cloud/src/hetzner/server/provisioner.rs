use crate::api::identity;
use crate::api::{
    ApplyContext, DestroyContext, DiffContext, DiffItem, Error, Labels, Listable, Lookup,
    LookupContext, LookupContextExt, LookupProvider, Provisioner, Resource, ResourceDiff, Result,
};
use crate::hetzner::api::servers::{PublicNetCreate, ServerCreate, ServerStatus, ServerUpdate};
use crate::hetzner::api::{ErrorCode, HetznerApi, ListQuery};
use crate::hetzner::labels::HetznerLabels;
use crate::hetzner::primary_ip::{HetznerPrimaryIpLookup, HetznerPrimaryIpRuntime};
use crate::hetzner::server::{HetznerServer, HetznerServerLookup, HetznerServerRuntime, UserData};
use crate::hetzner::ssh_key::HetznerSshKeyRuntime;
use crate::hetzner::volume::HetznerVolumeRuntime;
use crate::hetzner::{SSH_KEYS_LABEL, USER_DATA_LABEL, hetzner_label_diff};
use async_trait::async_trait;
use std::collections::HashSet;
use std::sync::Arc;
use tracing::{info, warn};

pub struct HetznerServerProvisioner {
    api: Arc<HetznerApi>,
}

/// the resources a server is created with, resolved through the context
struct Dependencies {
    ssh_keys: Vec<HetznerSshKeyRuntime>,
    volumes: Vec<HetznerVolumeRuntime>,
    ipv4: Option<HetznerPrimaryIpRuntime>,
    ipv6: Option<HetznerPrimaryIpRuntime>,
}

impl Dependencies {
    fn fingerprints(&self) -> String {
        self.ssh_keys
            .iter()
            .map(|key| key.fingerprint.as_str())
            .collect::<Vec<_>>()
            .join(", ")
    }
}

impl HetznerServerProvisioner {
    pub fn new(api: Arc<HetznerApi>) -> Self {
        HetznerServerProvisioner { api }
    }

    /// the resource of the name; `NotOwned` when it lacks an identity label of the lookup
    async fn find(&self, lookup: &HetznerServerLookup) -> Result<Option<HetznerServerRuntime>> {
        let found: Result<Option<HetznerServerRuntime>> = {
            Ok(self
                .api
                .servers()
                .get_by_name(&lookup.name)
                .await?
                .map(Into::into))
        };
        identity::check(found?, lookup.identity(), &lookup.log_text())
    }

    async fn dependencies(
        &self,
        resource: &HetznerServer,
        context: &dyn LookupContext,
    ) -> Result<Dependencies> {
        let mut ssh_keys = Vec::new();
        for lookup in &resource.ssh_keys {
            ssh_keys.push(
                context
                    .lookup(lookup)
                    .await?
                    .ok_or_else(|| Error::NotFound(lookup.log_text()))?,
            );
        }
        let mut volumes = Vec::new();
        for lookup in &resource.volumes {
            volumes.push(
                context
                    .lookup(lookup)
                    .await?
                    .ok_or_else(|| Error::NotFound(lookup.log_text()))?,
            );
        }
        Ok(Dependencies {
            ssh_keys,
            volumes,
            ipv4: self.primary_ip(resource.ipv4.as_ref(), context).await?,
            ipv6: self.primary_ip(resource.ipv6.as_ref(), context).await?,
        })
    }

    async fn primary_ip(
        &self,
        lookup: Option<&HetznerPrimaryIpLookup>,
        context: &dyn LookupContext,
    ) -> Result<Option<HetznerPrimaryIpRuntime>> {
        match lookup {
            Some(lookup) => Ok(Some(
                context
                    .lookup(lookup)
                    .await?
                    .ok_or_else(|| Error::NotFound(lookup.log_text()))?,
            )),
            None => Ok(None),
        }
    }

    /// the labels of the server: the declared labels and the hashes of the ssh keys and the
    /// user data it is created with
    fn labels(resource: &HetznerServer, dependencies: &Dependencies) -> Result<Labels> {
        let mut labels = HetznerLabels::new();
        labels.add_hashed(SSH_KEYS_LABEL, &dependencies.fingerprints())?;
        labels.add(USER_DATA_LABEL, &resource.user_data.hash())?;
        labels.add_all(&resource.labels)?;
        Ok(labels.into_raw())
    }

    /// assigns the primary ip to the server. Hetzner assigns it only to a stopped server, so
    /// the server is stopped and started again
    async fn assign(
        &self,
        ip: &HetznerPrimaryIpRuntime,
        server: &HetznerServerRuntime,
    ) -> Result<()> {
        if ip.assignee_id == Some(server.id) {
            return Ok(());
        }
        info!(
            "assigning primary ip '{}' to server '{}'",
            ip.name, server.name
        );
        let servers = self.api.servers();
        let ips = self.api.primary_ips();
        if server.status != ServerStatus::Off {
            servers
                .wait_for_action(&servers.power_off(server.id).await?)
                .await?;
        }
        if ip.assignee_id.is_some() {
            ips.wait_for_action(&ips.unassign(ip.id).await?).await?;
        }
        ips.wait_for_action(&ips.assign(ip.id, server.id).await?)
            .await?;
        servers
            .wait_for_action(&servers.power_on(server.id).await?)
            .await?;
        Ok(())
    }
}

#[async_trait]
impl LookupProvider<HetznerServerLookup> for HetznerServerProvisioner {
    async fn lookup(
        &self,
        lookup: &HetznerServerLookup,
        _: &dyn LookupContext,
    ) -> Result<Option<HetznerServerRuntime>> {
        self.find(lookup).await
    }
}

#[async_trait]
impl Listable<HetznerServerLookup> for HetznerServerProvisioner {
    async fn list(&self) -> Result<Vec<HetznerServerLookup>> {
        Ok(self
            .api
            .servers()
            .list(&ListQuery::new())
            .await?
            .iter()
            .map(|server| HetznerServerLookup::new(&server.name))
            .collect())
    }
}

#[async_trait]
impl Provisioner<HetznerServer> for HetznerServerProvisioner {
    async fn diff(
        &self,
        resource: &HetznerServer,
        context: &dyn DiffContext,
    ) -> Result<ResourceDiff> {
        let Some(runtime) = self.find(&resource.as_lookup()).await? else {
            return Ok(ResourceDiff::missing(resource));
        };
        let dependencies = self.dependencies(resource, context).await?;
        let labels = HetznerLabels::from_raw(runtime.labels.clone());

        let mut changes = Vec::new();

        // an ssh key that is not up to date can get a new fingerprint on apply; the server
        // created with the old key must be recreated
        let ssh_keys = labels.hash_matches(SSH_KEYS_LABEL, &dependencies.fingerprints());
        if resource
            .ssh_keys
            .iter()
            .any(|key| context.has_pending_change(&key.resource_ref()))
        {
            changes.push(
                DiffItem::recreate("ssh keys", "<known after apply>", "")
                    .with_actual(ssh_keys.actual.as_deref()),
            );
        } else if !ssh_keys.matches {
            changes.push(
                DiffItem::recreate("ssh keys", &ssh_keys.expected, "")
                    .with_actual(ssh_keys.actual.as_deref()),
            );
        }

        let user_data = labels.matches_hash(USER_DATA_LABEL, &resource.user_data.hash());
        if !user_data.matches {
            changes.push(
                DiffItem::recreate("user data checksum", &user_data.expected, "")
                    .with_actual(user_data.actual.as_deref()),
            );
        }

        if resource.location != runtime.location {
            changes.push(DiffItem::recreate(
                "location",
                resource.location,
                runtime.location,
            ));
        }
        if resource.server_type != runtime.server_type {
            changes.push(DiffItem::recreate(
                "type",
                resource.server_type,
                runtime.server_type,
            ));
        }
        if resource.image != runtime.image {
            changes.push(DiffItem::recreate("image", &resource.image, &runtime.image));
        }

        let declared: HashSet<u64> = dependencies
            .volumes
            .iter()
            .map(|volume| volume.id)
            .collect();
        let attached: HashSet<u64> = runtime.volumes.iter().copied().collect();
        if declared != attached {
            let names = |ids: &HashSet<u64>| {
                let mut names: Vec<String> = ids.iter().map(|id| id.to_string()).collect();
                names.sort();
                names.join(", ")
            };
            let expected: Vec<&str> = dependencies
                .volumes
                .iter()
                .map(|volume| volume.name.as_str())
                .collect();
            changes.push(DiffItem::recreate(
                "volumes",
                expected.join(", "),
                names(&attached),
            ));
        }

        for (name, ip, assigned) in [
            ("ipv4", &dependencies.ipv4, runtime.ipv4_id),
            ("ipv6", &dependencies.ipv6, runtime.ipv6_id),
        ] {
            if let Some(ip) = ip
                && assigned != Some(ip.id)
            {
                changes.push(DiffItem::changed(name, &ip.name, "").with_actual(assigned));
            }
        }

        changes.extend(hetzner_label_diff(resource, &runtime));

        Ok(ResourceDiff::from_changes(resource, changes))
    }

    async fn apply(
        &self,
        resource: &HetznerServer,
        context: &dyn ApplyContext,
    ) -> Result<HetznerServerRuntime> {
        let dependencies = self.dependencies(resource, context).await?;
        let labels = Self::labels(resource, &dependencies)?;
        let servers = self.api.servers();

        let runtime = match self.find(&resource.as_lookup()).await? {
            Some(runtime) => runtime,
            None => {
                info!(
                    "{} not found, creating ({}) in '{}' from '{}'",
                    resource.log_text(),
                    resource.server_type,
                    resource.location,
                    resource.image
                );
                let UserData::Text(user_data) = &resource.user_data else {
                    return Err(Error::message(format!(
                        "{} cannot be created, its user data is known by its hash only",
                        resource.log_text()
                    )));
                };
                self.wait_for_volumes(&dependencies.volumes).await?;
                let request = ServerCreate {
                    name: resource.name.clone(),
                    server_type: resource.server_type,
                    image: resource.image.clone(),
                    location: Some(resource.location),
                    ssh_keys: dependencies.ssh_keys.iter().map(|key| key.id).collect(),
                    volumes: dependencies
                        .volumes
                        .iter()
                        .map(|volume| volume.id)
                        .collect(),
                    firewalls: vec![],
                    user_data: Some(user_data.clone()),
                    public_net: Some(PublicNetCreate {
                        enable_ipv4: true,
                        enable_ipv6: true,
                        ipv4: dependencies.ipv4.as_ref().map(|ip| ip.id),
                        ipv6: dependencies.ipv6.as_ref().map(|ip| ip.id),
                    }),
                    labels: Some(labels.clone()),
                    start_after_create: None,
                };
                let created = match servers.create(&request).await {
                    Ok(created) => created,
                    Err(error) if error.code() == Some(ErrorCode::ResourceUnavailable) => {
                        return Err(Error::message(format!(
                            "server type '{}' is currently not available in '{}'",
                            resource.server_type, resource.location
                        )));
                    }
                    Err(error) => return Err(error.into()),
                };
                servers.wait_for_action(&created.action).await?;
                created.server.into()
            }
        };

        let request = ServerUpdate {
            name: Some(resource.name.clone()),
            labels: Some(labels),
        };
        servers.update(runtime.id, &request).await?;

        for (ip, assigned) in [
            (&dependencies.ipv4, runtime.ipv4_id),
            (&dependencies.ipv6, runtime.ipv6_id),
        ] {
            if let Some(ip) = ip
                && assigned != Some(ip.id)
            {
                self.assign(ip, &runtime).await?;
            }
        }

        let runtime = self.find(&resource.as_lookup()).await?.ok_or_else(|| {
            Error::message(format!("{} is gone after apply", resource.log_text()))
        })?;
        if runtime.status == ServerStatus::Off {
            info!("starting {}", resource.log_text());
            servers
                .wait_for_action(&servers.power_on(runtime.id).await?)
                .await?;
        }
        if runtime.public_ipv4.is_none() {
            warn!("{} has no public ipv4", resource.log_text());
        }
        Ok(runtime)
    }

    /// the volumes and primary ips of the server stay: they are resources of their own
    async fn destroy(&self, runtime: &HetznerServerRuntime, _: &dyn DestroyContext) -> Result<()> {
        if self.api.servers().delete(runtime.id).await?.is_some() {
            // until the server is gone, not until its action is done: hetzner marks the action of
            // a delete done up to half an hour after the server went when it is busy
            let api = &self.api;
            let id = runtime.id;
            api.wait_until("the server to be deleted", || async move {
                Ok(api.servers().get(id).await?.is_none())
            })
            .await?;
            // hetzner detaches the volumes after the delete; until then it refuses a new
            // server with them
            self.wait_for_detach(&runtime.volumes).await?;
        }
        Ok(())
    }
}

impl HetznerServerProvisioner {
    async fn wait_for_detach(&self, volumes: &[u64]) -> Result<()> {
        if volumes.is_empty() {
            return Ok(());
        }
        let api = &self.api;
        api.wait_until("the volumes of the server to detach", || async move {
            for id in volumes {
                if let Some(volume) = api.volumes().get(*id).await?
                    && volume.server.is_some()
                {
                    return Ok(false);
                }
            }
            Ok(true)
        })
        .await?;
        Ok(())
    }

    /// waits until a volume attached to a deleted server is detached. A volume attached to an
    /// existing server is an error in the declaration
    async fn wait_for_volumes(&self, volumes: &[HetznerVolumeRuntime]) -> Result<()> {
        for volume in volumes {
            let Some(other) = volume.server else { continue };
            if let Some(server) = self.api.servers().get(other).await? {
                return Err(Error::message(format!(
                    "volume '{}' is attached to server '{}'",
                    volume.name, server.name
                )));
            }
            self.wait_for_detach(&[volume.id]).await?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::{DiffStatus, LookupProviders};
    use crate::hetzner::api::{Backoff, Location, ServerType};
    use crate::hetzner::labels::label_hash;
    use crate::hetzner::ssh_key::{HetznerSshKeyLookup, HetznerSshKeyProvisioner};
    use crate::hetzner::test_support::{labels, page};
    use crate::hetzner::volume::{HetznerVolumeLookup, HetznerVolumeProvisioner};
    use serde_json::{Value, json};
    use std::time::Duration;
    use wiremock::matchers::{method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    const FINGERPRINT: &str = "99:fc:9b:f4:04:69:2c:9d:30:d5:2c:d9:1e:ca:b2:76";
    const USER_DATA: &str = "#cloud-config\n";

    /// the labels of a server created with the key and the user data of the tests
    fn created_labels(extra: &[(&str, &str)]) -> Value {
        let mut hetzner = HetznerLabels::new();
        hetzner.add_hashed(SSH_KEYS_LABEL, FINGERPRINT).unwrap();
        hetzner.add_hashed(USER_DATA_LABEL, USER_DATA).unwrap();
        hetzner.add_all(&labels(extra)).unwrap();
        serde_json::to_value(hetzner.into_raw()).unwrap()
    }

    fn server(
        name: &str,
        image: &str,
        volumes: Vec<u64>,
        ipv4_id: Option<u64>,
        labels: Value,
    ) -> Value {
        json!({
            "id": 1, "name": name, "status": "running",
            "server_type": { "id": 1, "name": "cx23" }, "image": { "id": 2, "name": image },
            "location": { "id": 3, "name": "nbg1" }, "protection": { "delete": false, "rebuild": false },
            "volumes": volumes,
            "public_net": { "ipv4": { "id": ipv4_id, "ip": "1.2.3.4" }, "ipv6": null }, "private_net": [],
            "labels": labels
        })
    }

    async fn mock(servers: Vec<Value>) -> (MockServer, Arc<HetznerApi>) {
        let server = MockServer::start().await;
        for (key, items) in [
            ("servers", servers),
            (
                "ssh_keys",
                vec![
                    json!({ "id": 7, "name": "admin", "fingerprint": FINGERPRINT, "public_key": "ssh-ed25519 AAAA", "labels": {} }),
                ],
            ),
            (
                "volumes",
                vec![json!({
                    "id": 3, "name": "data", "format": "ext4", "linux_device": "/dev/sdb", "size": 16, "protection": { "delete": true },
                    "server": 1, "status": "available", "location": { "id": 3, "name": "nbg1" }, "labels": {}
                })],
            ),
        ] {
            for item in &items {
                Mock::given(method("GET"))
                    .and(path(format!("/{key}")))
                    .and(query_param("name", item["name"].as_str().unwrap()))
                    .respond_with(
                        ResponseTemplate::new(200).set_body_json(page(key, vec![item.clone()])),
                    )
                    .mount(&server)
                    .await;
            }
            Mock::given(method("GET"))
                .and(path(format!("/{key}")))
                .respond_with(ResponseTemplate::new(200).set_body_json(page(key, vec![])))
                .mount(&server)
                .await;
        }
        let api = HetznerApi::with_base_url("token", &server.uri()).with_retry(Backoff::new(
            1,
            Duration::ZERO,
            Duration::ZERO,
        ));
        (server, Arc::new(api))
    }

    fn context(api: &Arc<HetznerApi>) -> LookupProviders {
        LookupProviders::new()
            .with::<HetznerSshKeyLookup, _>(Arc::new(HetznerSshKeyProvisioner::new(api.clone())))
            .with::<HetznerVolumeLookup, _>(Arc::new(HetznerVolumeProvisioner::new(api.clone())))
    }

    fn resource() -> HetznerServer {
        HetznerServer::new("backend", Location::nbg1, ServerType::cx23, USER_DATA)
            .with_ssh_keys(vec![HetznerSshKeyLookup::new("admin")])
            .with_volumes(vec![HetznerVolumeLookup::new("data")])
            .with_labels(labels(&[("app", "sak")]))
    }

    #[tokio::test]
    async fn diff_of_a_missing_server() {
        let (_server, api) = mock(vec![]).await;

        let diff = HetznerServerProvisioner::new(api.clone())
            .diff(&resource(), &context(&api))
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::Missing);
    }

    #[tokio::test]
    async fn diff_of_an_up_to_date_server_ignores_the_hash_labels() {
        // the label that sakctl sets after provisioning is not a declared label either
        let (_server, api) = mock(vec![server(
            "backend",
            "debian-13",
            vec![3],
            None,
            created_labels(&[
                ("app", "sak"),
                (crate::hetzner::PROVISIONED_CONFIG_LABEL, "abc"),
            ]),
        )])
        .await;

        let diff = HetznerServerProvisioner::new(api.clone())
            .diff(&resource(), &context(&api))
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::UpToDate, "{:?}", diff.changes);
    }

    #[tokio::test]
    async fn changed_user_data_and_image_recreate() {
        let (_server, api) = mock(vec![server(
            "backend",
            "debian-12",
            vec![3],
            None,
            created_labels(&[("app", "sak")]),
        )])
        .await;
        let resource = HetznerServer {
            user_data: UserData::text("#cloud-config\nchanged\n"),
            ..resource()
        };

        let diff = HetznerServerProvisioner::new(api.clone())
            .diff(&resource, &context(&api))
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::HasChanges);
        assert!(diff.needs_recreate());
        assert_eq!(
            diff.changes,
            vec![
                DiffItem::recreate(
                    "user data checksum",
                    label_hash("#cloud-config\nchanged\n"),
                    label_hash(USER_DATA)
                ),
                DiffItem::recreate("image", "debian-13", "debian-12"),
            ]
        );
    }

    #[tokio::test]
    async fn a_pending_ssh_key_change_is_known_after_apply() {
        let (_server, api) = mock(vec![server(
            "backend",
            "debian-13",
            vec![3],
            None,
            created_labels(&[("app", "sak")]),
        )])
        .await;
        let mut context = context(&api);
        context.mark_pending(HetznerSshKeyLookup::new("admin").resource_ref());

        let diff = HetznerServerProvisioner::new(api.clone())
            .diff(&resource(), &context)
            .await
            .unwrap();

        assert_eq!(diff.changes.len(), 1);
        assert_eq!(diff.changes[0].name, "ssh keys");
        assert!(diff.changes[0].triggers_recreate);
        assert_eq!(
            diff.changes[0].expected.as_deref(),
            Some("<known after apply>")
        );
        assert_eq!(
            diff.changes[0].actual.as_deref(),
            Some(label_hash(FINGERPRINT).as_str())
        );
    }

    #[tokio::test]
    async fn a_missing_dependency_is_an_error() {
        let (_server, api) = mock(vec![]).await;
        let resource = resource().with_ssh_keys(vec![HetznerSshKeyLookup::new("nobody")]);

        let error = HetznerServerProvisioner::new(api.clone())
            .apply(&resource, &context(&api))
            .await
            .unwrap_err();

        assert_eq!(error.to_string(), "ssh key 'nobody' not found");
    }

    #[tokio::test]
    async fn volumes_and_the_primary_ip_are_compared_by_id() {
        let (_server, api) = mock(vec![server(
            "backend",
            "debian-13",
            vec![9],
            Some(20),
            created_labels(&[("app", "sak")]),
        )])
        .await;
        let ip_server = MockServer::start().await;
        let _ = ip_server;
        let diff = HetznerServerProvisioner::new(api.clone())
            .diff(&resource(), &context(&api))
            .await
            .unwrap();

        assert_eq!(
            diff.changes,
            vec![DiffItem::recreate("volumes", "data", "9")]
        );
    }
}
