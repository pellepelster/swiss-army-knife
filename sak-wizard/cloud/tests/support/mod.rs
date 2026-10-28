//! the tests against the real hetzner cloud api: every resource a test creates carries
//! the id of the test as a label, and everything with that label is deleted at the end,
//! whether the test passed or panicked. Without `HCLOUD_TOKEN` the tests skip themselves.

#![allow(dead_code)]

use sak_cloud::api::Labels;
use sak_cloud::hetzner::api::firewalls::FirewallResource;
use sak_cloud::hetzner::api::{HetznerApi, ListQuery};
use std::future::Future;
use std::sync::Arc;

pub const TEST_ID_LABEL: &str = "sak/test-id";

pub struct HetznerTest {
    pub api: Arc<HetznerApi>,
    pub test_id: String,
    /// on every resource a test creates
    pub labels: Labels,
}

impl HetznerTest {
    /// `None` without a token, the test then returns right away
    pub fn start() -> Option<Arc<Self>> {
        let token = std::env::var("HCLOUD_TOKEN").unwrap_or_default();
        if token.is_empty() {
            eprintln!("HCLOUD_TOKEN is not set, skipping the test against the hetzner cloud api");
            return None;
        }
        let test_id = uuid::Uuid::new_v4().to_string();
        let labels = Labels::from([
            (TEST_ID_LABEL.to_string(), test_id.clone()),
            ("sak/managed-by".to_string(), "test".to_string()),
        ]);
        eprintln!(
            "the resources of this test are labeled {TEST_ID_LABEL}={test_id} and removed at its end"
        );
        Some(Arc::new(HetznerTest {
            api: Arc::new(HetznerApi::new(&token)),
            test_id,
            labels,
        }))
    }

    /// the labels of the test plus `extra`
    pub fn labels_with(&self, extra: &[(&str, &str)]) -> Labels {
        let mut labels = self.labels.clone();
        labels.extend(
            extra
                .iter()
                .map(|(key, value)| (key.to_string(), value.to_string())),
        );
        labels
    }

    /// runs the test body, then the cleanup, then fails the test when the body panicked
    pub async fn run<F, Fut>(self: Arc<Self>, body: F)
    where
        F: FnOnce(Arc<HetznerTest>) -> Fut,
        Fut: Future<Output = ()> + Send + 'static,
    {
        let result = tokio::spawn(body(self.clone())).await;
        self.cleanup().await;
        if let Err(error) = result {
            std::panic::resume_unwind(error.into_panic());
        }
    }

    /// deletes everything labeled with the test id, in the order the dependencies allow
    pub async fn cleanup(&self) {
        let query = ListQuery::new().label(TEST_ID_LABEL, &self.test_id);
        let api = &self.api;

        match api.servers().list(&query).await {
            Ok(servers) => {
                for server in servers {
                    eprintln!("cleanup: deleting server '{}'", server.name);
                    match api.servers().delete(server.id).await {
                        Ok(Some(action)) => log_error(api.servers().wait_for_action(&action).await),
                        Ok(None) => {}
                        Err(error) => eprintln!("cleanup: {error}"),
                    }
                }
            }
            Err(error) => eprintln!("cleanup: {error}"),
        }

        match api.primary_ips().list(&query).await {
            Ok(ips) => {
                for ip in ips {
                    eprintln!("cleanup: deleting primary ip '{}'", ip.name);
                    if ip.protection.delete
                        && let Ok(action) = api.primary_ips().change_protection(ip.id, false).await
                    {
                        log_error(api.primary_ips().wait_for_action(&action).await);
                    }
                    if ip.assignee_id.is_some()
                        && let Ok(action) = api.primary_ips().unassign(ip.id).await
                    {
                        log_error(api.primary_ips().wait_for_action(&action).await);
                    }
                    log_error(api.primary_ips().delete(ip.id).await);
                }
            }
            Err(error) => eprintln!("cleanup: {error}"),
        }

        match api.firewalls().list(&query).await {
            Ok(firewalls) => {
                for firewall in firewalls {
                    eprintln!("cleanup: deleting firewall '{}'", firewall.name);
                    if !firewall.applied_to.is_empty() {
                        let resources: Vec<FirewallResource> = firewall.applied_to.clone();
                        if let Ok(actions) = api
                            .firewalls()
                            .remove_from_resources(firewall.id, &resources)
                            .await
                        {
                            log_error(api.firewalls().wait_for_actions(&actions).await);
                        }
                    }
                    log_error(api.firewalls().delete(firewall.id).await);
                }
            }
            Err(error) => eprintln!("cleanup: {error}"),
        }

        match api.volumes().list(&query).await {
            Ok(volumes) => {
                for volume in volumes {
                    eprintln!("cleanup: deleting volume '{}'", volume.name);
                    if volume.protection.delete
                        && let Ok(action) = api.volumes().change_protection(volume.id, false).await
                    {
                        log_error(api.volumes().wait_for_action(&action).await);
                    }
                    if volume.server.is_some()
                        && let Ok(action) = api.volumes().detach(volume.id).await
                    {
                        log_error(api.volumes().wait_for_action(&action).await);
                    }
                    log_error(api.volumes().delete(volume.id).await);
                }
            }
            Err(error) => eprintln!("cleanup: {error}"),
        }

        match api.ssh_keys().list(&query).await {
            Ok(keys) => {
                for key in keys {
                    eprintln!("cleanup: deleting ssh key '{}'", key.name);
                    log_error(api.ssh_keys().delete(key.id).await);
                }
            }
            Err(error) => eprintln!("cleanup: {error}"),
        }
    }
}

fn log_error<T>(result: Result<T, sak_cloud::hetzner::api::ApiError>) {
    if let Err(error) = result {
        eprintln!("cleanup: {error}");
    }
}
