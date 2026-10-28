use ed25519_dalek::SigningKey;
use reqwest::{Client, Response, StatusCode};
use rust_i18n::t;
use serde::Deserialize;
use serde_json::json;
use std::time::Duration;

const API: &str = "https://api.hetzner.cloud/v1";

/// the hetzner cloud api, as far as sak-wizard uses it
pub struct HetznerCloud {
    client: Client,
    api: String,
}

#[derive(Deserialize)]
struct SshKeyResponse {
    ssh_key: SshKey,
}

#[derive(Deserialize)]
struct SshKey {
    id: u64,
}

#[derive(Deserialize)]
struct ErrorResponse {
    error: ApiError,
}

#[derive(Deserialize)]
struct ApiError {
    message: String,
}

impl HetznerCloud {
    pub fn new() -> Self {
        Self::with_api(API)
    }

    fn with_api(api: &str) -> Self {
        let client = Client::builder()
            .user_agent(concat!("sak-wizard/", env!("CARGO_PKG_VERSION")))
            .timeout(Duration::from_secs(30))
            .build()
            .expect("the http client has no configuration that can fail");
        Self {
            client,
            api: api.to_string(),
        }
    }

    // a read only token can list but not create, so the check writes: an ssh key is the
    // cheapest resource there is, it costs nothing and is deleted right away
    pub async fn validate(&self, token: &str) -> Result<(), String> {
        let name = format!("sak-wizard-token-check-{}", hex(&random::<8>()?));
        let response = self
            .client
            .post(format!("{}/ssh_keys", self.api))
            .bearer_auth(token)
            .json(&json!({ "name": name, "public_key": public_key()? }))
            .send()
            .await
            .map_err(|e| t!("providers.hetzner.unreachable", error = e).to_string())?;
        let created: SshKeyResponse = refused(response)
            .await?
            .json()
            .await
            .map_err(|e| t!("providers.hetzner.refused", error = e).to_string())?;

        let not_deleted = |error: String| {
            t!("providers.hetzner.not_deleted", name = name, error = error).to_string()
        };
        let response = self
            .client
            .delete(format!("{}/ssh_keys/{}", self.api, created.ssh_key.id))
            .bearer_auth(token)
            .send()
            .await
            .map_err(|e| not_deleted(e.to_string()))?;
        if !response.status().is_success() {
            return Err(not_deleted(error_message(response).await));
        }
        Ok(())
    }
}

// the response itself when the api accepted the request, why it did not otherwise
async fn refused(response: Response) -> Result<Response, String> {
    match response.status() {
        status if status.is_success() => Ok(response),
        StatusCode::UNAUTHORIZED => Err(t!("providers.hetzner.unauthorized").to_string()),
        StatusCode::FORBIDDEN => Err(t!("providers.hetzner.forbidden").to_string()),
        _ => Err(t!(
            "providers.hetzner.refused",
            error = error_message(response).await
        )
        .to_string()),
    }
}

// the api answers `{"error": {"code": "…", "message": "…"}}`, the status when it did not
async fn error_message(response: Response) -> String {
    let status = response.status();
    match response.json::<ErrorResponse>().await {
        Ok(body) => body.error.message,
        Err(_) => status.to_string(),
    }
}

// a fresh key every time, its private half is thrown away
fn public_key() -> Result<String, String> {
    let key = SigningKey::from_bytes(&random::<32>()?).verifying_key();
    Ok(crate::keys::encode_public_key(&key, "sak-wizard"))
}

fn random<const N: usize>() -> Result<[u8; N], String> {
    let mut bytes = [0u8; N];
    getrandom::fill(&mut bytes).map_err(|e| e.to_string())?;
    Ok(bytes)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine;
    use base64::engine::general_purpose::STANDARD;
    use wiremock::matchers::{header, method, path};
    use wiremock::{Mock, MockServer, Request, ResponseTemplate};

    const TOKEN: &str = "the-token";

    fn creates_an_ssh_key(request: &Request) -> bool {
        let body: serde_json::Value = serde_json::from_slice(&request.body).unwrap_or_default();
        body["name"]
            .as_str()
            .is_some_and(|name| name.starts_with("sak-wizard-token-check-"))
            && body["public_key"]
                .as_str()
                .is_some_and(|key| key.starts_with("ssh-ed25519 "))
    }

    async fn api(create: ResponseTemplate, delete: ResponseTemplate) -> MockServer {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/ssh_keys"))
            .and(header("authorization", format!("Bearer {TOKEN}")))
            .and(creates_an_ssh_key)
            .respond_with(create)
            .mount(&server)
            .await;
        Mock::given(method("DELETE"))
            .and(path("/ssh_keys/42"))
            .and(header("authorization", format!("Bearer {TOKEN}")))
            .respond_with(delete)
            .expect(..=1)
            .mount(&server)
            .await;
        server
    }

    fn created() -> ResponseTemplate {
        ResponseTemplate::new(201).set_body_json(json!({ "ssh_key": { "id": 42 } }))
    }

    fn error(status: u16, message: &str) -> ResponseTemplate {
        ResponseTemplate::new(status)
            .set_body_json(json!({ "error": { "code": "error", "message": message } }))
    }

    #[tokio::test]
    async fn a_token_creating_and_deleting_an_ssh_key_is_valid() {
        let server = api(created(), ResponseTemplate::new(204)).await;
        assert_eq!(
            HetznerCloud::with_api(&server.uri()).validate(TOKEN).await,
            Ok(())
        );
    }

    #[tokio::test]
    async fn an_unknown_token_is_invalid() {
        let server = api(
            error(401, "unable to authenticate"),
            ResponseTemplate::new(204),
        )
        .await;
        assert_eq!(
            HetznerCloud::with_api(&server.uri()).validate(TOKEN).await,
            Err("The token is not valid, check that it was copied completely.".to_string())
        );
    }

    #[tokio::test]
    async fn a_read_only_token_is_invalid() {
        let server = api(
            error(403, "insufficient permissions"),
            ResponseTemplate::new(204),
        )
        .await;
        assert_eq!(
            HetznerCloud::with_api(&server.uri()).validate(TOKEN).await,
            Err(
                "The token can only read, create one with the permissions read & write."
                    .to_string()
            )
        );
    }

    #[tokio::test]
    async fn the_ssh_key_left_behind_is_named() {
        let server = api(created(), error(500, "server error")).await;
        let error = HetznerCloud::with_api(&server.uri())
            .validate(TOKEN)
            .await
            .unwrap_err();
        assert!(error.contains("sak-wizard-token-check-"), "{error}");
        assert!(error.ends_with("server error"), "{error}");
    }

    #[test]
    fn the_public_key_is_an_openssh_ed25519_key() {
        let key = public_key().unwrap();
        let blob = STANDARD.decode(key.split(' ').nth(1).unwrap()).unwrap();
        assert_eq!(&blob[..15], b"\0\0\0\x0bssh-ed25519");
        assert_eq!(blob.len(), 4 + 11 + 4 + 32);
    }

    // against the real api, with the token of HCLOUD_TOKEN when it is set
    #[tokio::test]
    async fn the_token_of_hcloud_token_is_valid() {
        let Ok(token) = std::env::var("HCLOUD_TOKEN") else {
            eprintln!("HCLOUD_TOKEN is not set, skipped");
            return;
        };
        assert_eq!(HetznerCloud::new().validate(&token).await, Ok(()));
    }
}
