//! the hetzner cloud api, as far as the provisioners use it: one struct per resource
//! with list, get, create, update and delete plus the actions of the resource

pub mod error;
pub mod firewalls;
pub mod model;
pub mod primary_ips;
pub mod servers;
pub mod ssh_keys;
pub mod volumes;

#[cfg(test)]
mod tests;

pub use error::{ApiError, ErrorCode};
pub use model::{Action, ActionStatus, ListQuery, Location, ServerType};

use error::ErrorResponse;
use model::{ActionResponse, ListPage};
use reqwest::{Client, RequestBuilder, Response, StatusCode};
use serde::Serialize;
use serde::de::DeserializeOwned;
use std::time::Duration;
use tracing::{debug, info};

const API: &str = "https://api.hetzner.cloud/v1";

/// hetzner lists at most 50 per page
const PAGE_SIZE: u32 = 50;

pub type Result<T> = std::result::Result<T, ApiError>;

/// how long to wait and how often to try again: a rate limited request, an action still
/// running. The delay doubles up to `max_delay`
#[derive(Clone, Copy, Debug)]
pub struct Backoff {
    pub attempts: u32,
    pub initial_delay: Duration,
    pub max_delay: Duration,
}

impl Backoff {
    pub const fn new(attempts: u32, initial_delay: Duration, max_delay: Duration) -> Self {
        Backoff {
            attempts,
            initial_delay,
            max_delay,
        }
    }

    fn delay(&self, attempt: u32) -> Duration {
        let delay = self
            .initial_delay
            .saturating_mul(2u32.saturating_pow(attempt));
        delay.min(self.max_delay)
    }
}

pub struct HetznerApi {
    client: Client,
    base: String,
    token: String,
    /// for requests the api rate limited
    retry: Backoff,
    /// for actions the api still runs
    wait: Backoff,
}

impl HetznerApi {
    pub fn new(token: &str) -> Self {
        Self::with_base_url(token, API)
    }

    /// against another address, the mock api of the tests
    pub fn with_base_url(token: &str, base: &str) -> Self {
        let client = Client::builder()
            .user_agent(concat!("sak-cloud/", env!("CARGO_PKG_VERSION")))
            .timeout(Duration::from_secs(60))
            .build()
            .expect("the http client has no configuration that can fail");
        HetznerApi {
            client,
            base: base.trim_end_matches('/').to_string(),
            token: token.to_string(),
            retry: Backoff::new(5, Duration::from_secs(1), Duration::from_secs(10)),
            wait: Backoff::new(40, Duration::from_secs(1), Duration::from_secs(5)),
        }
    }

    pub fn with_retry(mut self, retry: Backoff) -> Self {
        self.retry = retry;
        self
    }

    pub fn with_wait(mut self, wait: Backoff) -> Self {
        self.wait = wait;
        self
    }

    pub fn ssh_keys(&self) -> ssh_keys::SshKeysApi<'_> {
        ssh_keys::SshKeysApi { api: self }
    }

    pub fn servers(&self) -> servers::ServersApi<'_> {
        servers::ServersApi { api: self }
    }

    pub fn firewalls(&self) -> firewalls::FirewallsApi<'_> {
        firewalls::FirewallsApi { api: self }
    }

    pub fn volumes(&self) -> volumes::VolumesApi<'_> {
        volumes::VolumesApi { api: self }
    }

    pub fn primary_ips(&self) -> primary_ips::PrimaryIpsApi<'_> {
        primary_ips::PrimaryIpsApi { api: self }
    }

    fn url(&self, path: &str) -> String {
        format!("{}/{}", self.base, path)
    }

    /// `None` for a 404
    pub(crate) async fn get<T: DeserializeOwned>(
        &self,
        path: &str,
        query: &[(String, String)],
    ) -> Result<Option<T>> {
        let request = self.client.get(self.url(path)).query(query);
        let response = self.send(path, request).await?;
        self.handle_optional(path, response).await
    }

    pub(crate) async fn post<T: DeserializeOwned, B: Serialize>(
        &self,
        path: &str,
        body: Option<&B>,
    ) -> Result<T> {
        let mut request = self.client.post(self.url(path));
        if let Some(body) = body {
            request = request.json(body);
        }
        let response = self.send(path, request).await?;
        self.handle(path, response).await
    }

    pub(crate) async fn put<T: DeserializeOwned, B: Serialize>(
        &self,
        path: &str,
        body: &B,
    ) -> Result<T> {
        let request = self.client.put(self.url(path)).json(body);
        let response = self.send(path, request).await?;
        self.handle(path, response).await
    }

    /// `false` when there was nothing to delete
    pub(crate) async fn delete(&self, path: &str) -> Result<bool> {
        let response = self.send(path, self.client.delete(self.url(path))).await?;
        match response.status() {
            status if status.is_success() => Ok(true),
            StatusCode::NOT_FOUND => Ok(false),
            _ => Err(self.error(path, response).await),
        }
    }

    /// a delete the api answers with the action that runs it, `None` for a 404
    pub(crate) async fn delete_with_action(&self, path: &str) -> Result<Option<Action>> {
        let response = self.send(path, self.client.delete(self.url(path))).await?;
        Ok(self
            .handle_optional::<ActionResponse>(path, response)
            .await?
            .map(|response| response.action))
    }

    /// every page of a list, following `next_page`
    pub(crate) async fn list_all<P: ListPage>(
        &self,
        path: &str,
        query: &ListQuery,
    ) -> Result<Vec<P::Item>> {
        let mut items = Vec::new();
        let mut page = 1;
        loop {
            let response: P = self
                .get(path, &query.params(page, PAGE_SIZE))
                .await?
                .ok_or_else(|| ApiError::Unexpected {
                    path: path.to_string(),
                    status: 404,
                    body: "a list endpoint answered not found".to_string(),
                })?;
            let (page_items, meta) = response.into_parts();
            items.extend(page_items);
            match meta.pagination.next_page {
                Some(next) if next > page => page = next,
                _ => return Ok(items),
            }
        }
    }

    /// polls the action at `v1/<resource>/actions/<id>` until it ended, an error of the
    /// action is an error here
    pub(crate) async fn wait_for_action(&self, resource: &str, id: u64) -> Result<Action> {
        let path = format!("{resource}/actions/{id}");
        for attempt in 0..self.wait.attempts {
            let action = self
                .get::<ActionResponse>(&path, &[])
                .await?
                .ok_or_else(|| ApiError::Unexpected {
                    path: path.clone(),
                    status: 404,
                    body: "the action is gone".to_string(),
                })?
                .action;

            match action.status {
                ActionStatus::Success => return Ok(action),
                ActionStatus::Error => {
                    return Err(ApiError::ActionFailed {
                        id,
                        command: action.command,
                        message: action
                            .error
                            .map(|error| format!("{} ({})", error.message, error.code))
                            .unwrap_or_default(),
                    });
                }
                ActionStatus::Running => {
                    info!("waiting for '{}' ({}%)", action.command, action.progress);
                    if attempt + 1 < self.wait.attempts {
                        tokio::time::sleep(self.wait.delay(attempt)).await;
                    } else {
                        return Err(ApiError::ActionTimeout {
                            id,
                            command: action.command,
                        });
                    }
                }
            }
        }
        unreachable!("the wait has at least one attempt")
    }

    /// sends the request, again after a wait when the api rate limited it
    async fn send(&self, path: &str, request: RequestBuilder) -> Result<Response> {
        let request = request.bearer_auth(&self.token);
        for attempt in 0..self.retry.attempts {
            let attempt_request = request
                .try_clone()
                .expect("the requests have no stream bodies");
            debug!("{path}");
            let response = attempt_request
                .send()
                .await
                .map_err(|source| ApiError::Request {
                    path: path.to_string(),
                    source,
                })?;
            if response.status() != StatusCode::TOO_MANY_REQUESTS
                || attempt + 1 == self.retry.attempts
            {
                return Ok(response);
            }
            info!("rate limited on {path}, waiting");
            tokio::time::sleep(self.retry.delay(attempt)).await;
        }
        unreachable!("the retry has at least one attempt")
    }

    async fn handle<T: DeserializeOwned>(&self, path: &str, response: Response) -> Result<T> {
        if response.status().is_success() {
            return response.json().await.map_err(|source| ApiError::Request {
                path: path.to_string(),
                source,
            });
        }
        Err(self.error(path, response).await)
    }

    async fn handle_optional<T: DeserializeOwned>(
        &self,
        path: &str,
        response: Response,
    ) -> Result<Option<T>> {
        if response.status() == StatusCode::NOT_FOUND {
            return Ok(None);
        }
        self.handle(path, response).await.map(Some)
    }

    async fn error(&self, path: &str, response: Response) -> ApiError {
        let status = response.status().as_u16();
        let body = response.text().await.unwrap_or_default();
        match serde_json::from_str::<ErrorResponse>(&body) {
            Ok(error) => ApiError::Api {
                path: path.to_string(),
                code: error.error.code,
                message: error.error.message,
            },
            Err(_) => ApiError::Unexpected {
                path: path.to_string(),
                status,
                body,
            },
        }
    }
}

#[derive(Serialize)]
pub(crate) struct Empty {}

/// the body of a post without one
pub(crate) const NO_BODY: Option<&Empty> = None;
