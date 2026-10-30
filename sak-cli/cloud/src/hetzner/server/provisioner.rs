use crate::api::identity;
use crate::api::{
    ApplyContext, DestroyContext, DiffContext, DiffItem, Error, Labels, Lookup, LookupContext,
    LookupContextExt, LookupProvider, Provisioner, Resource, ResourceDiff, Result,
};
use crate::hetzner::api::servers::{PublicNetCreate, ServerCreate, ServerStatus, ServerUpdate};
use crate::hetzner::api::volumes::VolumeStatus;
use crate::hetzner::api::{ErrorCode, HetznerApi, ListQuery, Location, ServerType};
use crate::hetzner::labels::HetznerLabels;
use crate::hetzner::primary_ip::{HetznerPrimaryIpLookup, HetznerPrimaryIpRuntime};
use crate::hetzner::server::{HetznerServer, HetznerServerLookup, HetznerServerRuntime, UserData};
use crate::hetzner::ssh_key::{HetznerSshKeyLookup, HetznerSshKeyRuntime};
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

    /// the server of the name as the api has it, one it deletes included; `NotOwned` when it lacks
    /// an identity label of the lookup
    async fn find_any(&self, lookup: &HetznerServerLookup) -> Result<Option<HetznerServerRuntime>> {
        let found = self
            .api
            .servers()
            .get_by_name(&lookup.name)
            .await?
            .map(Into::into);
        identity::check_lookup(found, lookup)
    }

    /// the server of the name; one Hetzner deletes is gone already: a plan creates it anew, a
    /// destroy has nothing left to do
    async fn find(&self, lookup: &HetznerServerLookup) -> Result<Option<HetznerServerRuntime>> {
        Ok(self
            .find_any(lookup)
            .await?
            .filter(|server| server.status != ServerStatus::Deleting))
    }

    /// before a create: waits until a server of the name that Hetzner deletes is gone, its name
    /// is taken until then
    async fn wait_for_deleted(&self, lookup: &HetznerServerLookup) -> Result<()> {
        let Some(server) = self.find_any(lookup).await? else {
            return Ok(());
        };
        if server.status != ServerStatus::Deleting {
            return Ok(());
        }
        let (api, id) = (&self.api, server.id);
        api.wait_until("the server to be deleted", || async move {
            Ok(api.servers().get(id).await?.is_none())
        })
        .await?;
        Ok(())
    }

    async fn dependencies(
        &self,
        resource: &HetznerServer,
        context: &dyn LookupContext,
    ) -> Result<Dependencies> {
        let mut ssh_keys = Vec::new();
        for lookup in &resource.ssh_keys {
            match context.lookup(lookup).await? {
                Some(key) => ssh_keys.push(key),
                None => return Err(self.missing_key(lookup).await?),
            }
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

    /// the error of a declared ssh key that is not found; it names a key of another name with the
    /// identity labels of the lookup, a key renamed in the console
    async fn missing_key(&self, lookup: &HetznerSshKeyLookup) -> Result<Error> {
        if lookup.identity.is_empty() {
            return Ok(Error::NotFound(lookup.log_text()));
        }
        let renamed = self
            .api
            .ssh_keys()
            .list(&ListQuery::new().labels(&lookup.identity))
            .await?
            .into_iter()
            .find(|key| {
                key.name != lookup.name
                    && lookup
                        .identity
                        .iter()
                        .all(|(label, value)| key.labels.get(label) == Some(value))
            });
        Ok(match renamed {
            Some(key) => Error::message(format!(
                "{} not found, the ssh key '{}' has its labels: rename it back to '{}' or remove it",
                lookup.log_text(),
                key.name,
                lookup.name
            )),
            None => Error::NotFound(lookup.log_text()),
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

    /// assigns the primary ips to the server, those it has not already. Hetzner assigns one only
    /// to a stopped server, so the server is stopped once and started again
    async fn assign(
        &self,
        ips: &[&HetznerPrimaryIpRuntime],
        server: &HetznerServerRuntime,
    ) -> Result<()> {
        let unassigned: Vec<&HetznerPrimaryIpRuntime> = ips
            .iter()
            .copied()
            .filter(|ip| ip.assignee_id != Some(server.id))
            .collect();
        if unassigned.is_empty() {
            return Ok(());
        }
        let servers = self.api.servers();
        let primary_ips = self.api.primary_ips();
        if server.status != ServerStatus::Off {
            servers
                .wait_for_action(&servers.power_off(server.id).await?)
                .await?;
        }
        for ip in unassigned {
            info!(
                "assigning primary ip '{}' to server '{}'",
                ip.name, server.name
            );
            if ip.assignee_id.is_some() {
                primary_ips
                    .wait_for_action(&primary_ips.unassign(ip.id).await?)
                    .await?;
            }
            primary_ips
                .wait_for_action(&primary_ips.assign(ip.id, server.id).await?)
                .await?;
        }
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

        // a location, type or image this sakctl does not know is no reason to recreate the server;
        // Hetzner forgets the image of a server once the image is deleted
        if resource.location != runtime.location && runtime.location != Location::unknown {
            changes.push(DiffItem::recreate(
                "location",
                resource.location,
                runtime.location,
            ));
        }
        if resource.server_type != runtime.server_type && runtime.server_type != ServerType::unknown
        {
            changes.push(DiffItem::recreate(
                "type",
                resource.server_type,
                runtime.server_type,
            ));
        }
        if let Some(image) = &runtime.image
            && *image != resource.image
        {
            changes.push(DiffItem::recreate("image", &resource.image, image));
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
                self.wait_for_deleted(&resource.as_lookup()).await?;
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

        // only when they differ: a created server has them already
        if runtime.labels != labels {
            let request = ServerUpdate {
                name: Some(resource.name.clone()),
                labels: Some(labels),
            };
            servers.update(runtime.id, &request).await?;
        }

        let unassigned: Vec<&HetznerPrimaryIpRuntime> = [
            (&dependencies.ipv4, runtime.ipv4_id),
            (&dependencies.ipv6, runtime.ipv6_id),
        ]
        .into_iter()
        .filter_map(|(ip, assigned)| ip.as_ref().filter(|ip| assigned != Some(ip.id)))
        .collect();
        self.assign(&unassigned, &runtime).await?;

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
        // a volume still being created, by this run or another, is locked until it is available
        let creating: Vec<u64> = volumes
            .iter()
            .filter(|volume| volume.status != VolumeStatus::Available)
            .map(|volume| volume.id)
            .collect();
        if !creating.is_empty() {
            let api = &self.api;
            let creating = &creating;
            api.wait_until("the volumes to be available", || async move {
                for id in creating {
                    if let Some(volume) = api.volumes().get(*id).await?
                        && volume.status != VolumeStatus::Available
                    {
                        return Ok(false);
                    }
                }
                Ok(true)
            })
            .await?;
        }
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
    use crate::api::context::DiffRun;
    use crate::api::{DiffStatus, LookupProviders};
    use crate::hetzner::api::{Backoff, Location, ServerType};
    use crate::hetzner::labels::label_hash;
    use crate::hetzner::primary_ip::HetznerPrimaryIpProvisioner;
    use crate::hetzner::ssh_key::{HetznerSshKeyLookup, HetznerSshKeyProvisioner};
    use crate::hetzner::test_support::{labels, page};
    use crate::hetzner::volume::{HetznerVolumeLookup, HetznerVolumeProvisioner};
    use serde_json::{Value, json};
    use std::time::Duration;
    use wiremock::matchers::{method, path, path_regex, query_param};
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

    /// an unassigned primary ip
    fn ip(id: u64, name: &str, ip_type: &str) -> Value {
        json!({
            "id": id, "name": name, "ip": "1.2.3.4", "type": ip_type, "assignee_id": null, "assignee_type": "unassigned",
            "auto_delete": false, "protection": { "delete": true }, "location": { "id": 3, "name": "nbg1" }, "labels": {}
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
            (
                "primary_ips",
                vec![ip(21, "ipv4", "ipv4"), ip(22, "ipv6", "ipv6")],
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
            .with::<HetznerPrimaryIpLookup, _>(Arc::new(HetznerPrimaryIpProvisioner::new(
                api.clone(),
            )))
    }

    fn resource() -> HetznerServer {
        HetznerServer::new("backend", Location::nbg1, ServerType::cx23, USER_DATA)
            .with_ssh_keys(vec![HetznerSshKeyLookup::new("admin")])
            .with_volumes(vec![HetznerVolumeLookup::new("data")])
            .with_labels(labels(&[("app", "sak")]))
    }

    #[tokio::test]
    async fn a_server_hetzner_deletes_is_gone_and_a_create_waits_for_its_name() {
        let mut deleting = server("backend", "debian-13", vec![3], None, created_labels(&[]));
        deleting["status"] = json!("deleting");
        let (mock_server, api) = mock(vec![deleting]).await;
        let provisioner = HetznerServerProvisioner::new(api.clone());

        let diff = provisioner.diff(&resource(), &context(&api)).await.unwrap();
        assert_eq!(diff.status, DiffStatus::Missing);

        // the api answers it no more once it is deleted
        Mock::given(method("GET"))
            .and(path("/servers/1"))
            .respond_with(ResponseTemplate::new(404).set_body_json(
                json!({ "error": { "code": "not_found", "message": "server not found" } }),
            ))
            .mount(&mock_server)
            .await;
        provisioner
            .wait_for_deleted(&resource().as_lookup())
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn a_server_waits_for_a_volume_that_is_still_being_created() {
        let (mock_server, api) = mock(vec![]).await;
        let mut available = json!({
            "id": 3, "name": "data", "format": "ext4", "linux_device": "/dev/sdb", "size": 16,
            "protection": { "delete": true }, "server": null, "status": "available",
            "location": { "id": 3, "name": "nbg1" }, "labels": {}
        });
        Mock::given(method("GET"))
            .and(path("/volumes/3"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(json!({ "volume": available.take() })),
            )
            .expect(1..)
            .mount(&mock_server)
            .await;
        let creating = HetznerVolumeRuntime {
            id: 3,
            name: "data".to_string(),
            device: "/dev/sdb".to_string(),
            size_gb: 16,
            location: Location::nbg1,
            server: None,
            delete_protected: true,
            status: VolumeStatus::Creating,
            labels: Default::default(),
        };

        HetznerServerProvisioner::new(api)
            .wait_for_volumes(&[creating])
            .await
            .unwrap();
        // the expectation on the volume is checked when the mock server goes
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
    async fn an_image_hetzner_deleted_is_no_recreate() {
        let mut deleted = server(
            "backend",
            "",
            vec![3],
            None,
            created_labels(&[("app", "sak")]),
        );
        deleted["image"] = Value::Null;
        let (_server, api) = mock(vec![deleted]).await;

        let diff = HetznerServerProvisioner::new(api.clone())
            .diff(&resource(), &context(&api))
            .await
            .unwrap();

        assert_eq!(diff.status, DiffStatus::UpToDate, "{:?}", diff.changes);
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
        let providers = context(&api);
        let mut context = DiffRun::new(&providers);
        context.mark_pending(HetznerSshKeyLookup::new("admin").resource_ref());

        let diff = HetznerServerProvisioner::new(api.clone())
            .diff(&resource(), &context)
            .await
            .unwrap();

        assert_eq!(diff.changes.len(), 1);
        assert_eq!(diff.changes[0].name, "ssh keys");
        assert!(diff.changes[0].triggers_recreate());
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
    async fn an_ssh_key_whose_labels_change_is_no_recreate_of_the_server() {
        let (_server, api) = mock(vec![server(
            "backend",
            "debian-13",
            vec![3],
            None,
            created_labels(&[("app", "sak")]),
        )])
        .await;
        // the key of the fingerprint, without the label it is declared with
        let key = crate::hetzner::ssh_key::HetznerSshKey::new(
            "admin",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE+u0dEVRZDzzp4E1teCqF49r8ig3YEk8eaPqNWfDcPb pelle@fry",
        )
        .with_labels(labels(&[("app", "sak")]));
        let registry = crate::providers::hetzner::HetznerProviderRuntime::from_api(api)
            .registry()
            .with(key)
            .unwrap()
            .with(resource())
            .unwrap();

        let diffs = registry.diff_all().await.unwrap();

        let statuses: Vec<(&str, DiffStatus)> = diffs
            .iter()
            .map(|diff| (diff.resource.name.as_str(), diff.status))
            .collect();
        assert_eq!(
            statuses,
            vec![
                ("admin", DiffStatus::HasChanges),
                ("backend", DiffStatus::UpToDate)
            ]
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
        let resource = resource().with_ipv4(HetznerPrimaryIpLookup::new("ipv4"));

        let diff = HetznerServerProvisioner::new(api.clone())
            .diff(&resource, &context(&api))
            .await
            .unwrap();

        // the volume 9 is not 3, the ip 20 is not 21
        assert_eq!(
            diff.changes,
            vec![
                DiffItem::recreate("volumes", "data", "9"),
                DiffItem::changed("ipv4", "ipv4", "").with_actual(Some(20)),
            ]
        );
    }

    #[tokio::test]
    async fn both_primary_ips_are_assigned_in_one_power_cycle() {
        let (mock_server, api) = mock(vec![server(
            "backend",
            "debian-13",
            vec![3],
            None,
            created_labels(&[("app", "sak")]),
        )])
        .await;
        let done = json!({
            "action": {
                "id": 1, "command": "any", "status": "success", "progress": 100,
                "started": "2026-01-01T00:00:00+00:00", "finished": null, "error": null, "resources": []
            }
        });
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(201).set_body_json(done.clone()))
            .mount(&mock_server)
            .await;
        Mock::given(method("GET"))
            .and(path_regex(r"/actions/\d+$"))
            .respond_with(ResponseTemplate::new(200).set_body_json(done))
            .mount(&mock_server)
            .await;
        let provisioner = HetznerServerProvisioner::new(api.clone());
        let lookup = |name| HetznerPrimaryIpLookup::new(name);
        let context = context(&api);
        let ipv4 = context.lookup(&lookup("ipv4")).await.unwrap().unwrap();
        let ipv6 = context.lookup(&lookup("ipv6")).await.unwrap().unwrap();
        let runtime = provisioner
            .find(&resource().as_lookup())
            .await
            .unwrap()
            .unwrap();

        provisioner.assign(&[&ipv4, &ipv6], &runtime).await.unwrap();

        let posts: Vec<String> = mock_server
            .received_requests()
            .await
            .unwrap()
            .iter()
            .filter(|request| request.method == wiremock::http::Method::POST)
            .map(|request| request.url.path().to_string())
            .collect();
        assert_eq!(
            posts,
            vec![
                "/servers/1/actions/poweroff",
                "/primary_ips/21/actions/assign",
                "/primary_ips/22/actions/assign",
                "/servers/1/actions/poweron",
            ]
        );
    }
}
