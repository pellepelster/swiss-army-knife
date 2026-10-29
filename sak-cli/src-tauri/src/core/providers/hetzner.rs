//! the check of a hetzner token, through the client of sak-cloud (`HetznerApi`), the one client of
//! the hetzner api sakctl has

use ed25519_dalek::SigningKey;
use rust_i18n::t;
use sak_cloud::api::Labels;
use sak_cloud::hetzner::TOKEN_CHECK_LABEL;
use sak_cloud::hetzner::api::ssh_keys::SshKeyCreate;
use sak_cloud::hetzner::api::{ApiError, ErrorCode, HetznerApi, ListQuery};

/// a read only token can list but not create, so the check writes: an ssh key is the cheapest
/// resource there is, it costs nothing and is deleted right away. It carries the label
/// `sak.pelle.io/token-check`, so a key a check left behind (a delete that failed) is told from
/// every other key and removed by the next check
pub async fn validate(api: &HetznerApi) -> Result<(), String> {
    let name = format!("sakctl-token-check-{}", hex(&random::<8>()?));
    let created = api
        .ssh_keys()
        .create(&SshKeyCreate {
            name: name.clone(),
            public_key: public_key()?,
            labels: Some(Labels::from([(
                TOKEN_CHECK_LABEL.to_string(),
                "true".to_string(),
            )])),
        })
        .await
        .map_err(refused)?;
    api.ssh_keys().delete(created.id).await.map_err(|error| {
        t!(
            "shared.providers.hetzner.not_deleted",
            name = name,
            error = message(&error)
        )
        .to_string()
    })?;
    remove_left_behind(api).await;
    Ok(())
}

/// the keys of earlier checks whose delete failed; one that cannot be removed now is left for the
/// next check, it does not make this one fail
async fn remove_left_behind(api: &HetznerApi) {
    let query = ListQuery::new().label(TOKEN_CHECK_LABEL, "true");
    let Ok(keys) = api.ssh_keys().list(&query).await else {
        return;
    };
    for key in keys {
        let _ = api.ssh_keys().delete(key.id).await;
    }
}

/// why the api did not take the token: unknown, read only, not reachable, or what it says
fn refused(error: ApiError) -> String {
    match &error {
        ApiError::Api {
            code: ErrorCode::Unauthorized,
            ..
        } => t!("shared.providers.hetzner.unauthorized").to_string(),
        ApiError::Api {
            code: ErrorCode::Forbidden,
            ..
        } => t!("shared.providers.hetzner.forbidden").to_string(),
        ApiError::Request { source, .. } => {
            t!("shared.providers.hetzner.unreachable", error = source).to_string()
        }
        _ => t!("shared.providers.hetzner.refused", error = message(&error)).to_string(),
    }
}

/// what the api says, without the path of the request
fn message(error: &ApiError) -> String {
    match error {
        ApiError::Api { message, .. } => message.clone(),
        other => other.to_string(),
    }
}

// a fresh key every time, its private half is thrown away
fn public_key() -> Result<String, String> {
    let key = SigningKey::from_bytes(&random::<32>()?).verifying_key();
    Ok(crate::core::keys::encode_public_key(&key, "sakctl"))
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
    use sak_cloud::hetzner::api::Backoff;
    use serde_json::json;
    use std::time::Duration;
    use wiremock::matchers::{header, method, path};
    use wiremock::{Mock, MockServer, Request, ResponseTemplate};

    const TOKEN: &str = "the-token";

    fn creates_an_ssh_key(request: &Request) -> bool {
        let body: serde_json::Value = serde_json::from_slice(&request.body).unwrap_or_default();
        body["name"]
            .as_str()
            .is_some_and(|name| name.starts_with("sakctl-token-check-"))
            && body["public_key"]
                .as_str()
                .is_some_and(|key| key.starts_with("ssh-ed25519 "))
            && body["labels"][TOKEN_CHECK_LABEL] == "true"
    }

    async fn api(create: ResponseTemplate, delete: ResponseTemplate) -> (MockServer, HetznerApi) {
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
            // a failed delete is sent once again
            .expect(..=2)
            .mount(&server)
            .await;
        let fast = Backoff::new(2, Duration::from_millis(1), Duration::from_millis(2));
        let api = HetznerApi::with_base_url(TOKEN, &server.uri()).with_retry(fast);
        (server, api)
    }

    fn created() -> ResponseTemplate {
        ResponseTemplate::new(201).set_body_json(json!({ "ssh_key": {
            "id": 42, "name": "sakctl-token-check-x", "fingerprint": "aa", "public_key": "ssh-ed25519 AAAA", "labels": {}
        } }))
    }

    fn error(status: u16, code: &str, message: &str) -> ResponseTemplate {
        ResponseTemplate::new(status)
            .set_body_json(json!({ "error": { "code": code, "message": message } }))
    }

    #[tokio::test]
    async fn a_token_creating_and_deleting_an_ssh_key_is_valid() {
        let (_server, api) = api(created(), ResponseTemplate::new(204)).await;
        assert_eq!(validate(&api).await, Ok(()));
    }

    #[tokio::test]
    async fn the_keys_earlier_checks_left_behind_are_removed() {
        let (server, api) = api(created(), ResponseTemplate::new(204)).await;
        Mock::given(method("GET"))
            .and(path("/ssh_keys"))
            .and(wiremock::matchers::query_param(
                "label_selector",
                format!("{TOKEN_CHECK_LABEL}==true"),
            ))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "ssh_keys": [{
                    "id": 41, "name": "sakctl-token-check-old", "fingerprint": "bb",
                    "public_key": "ssh-ed25519 BBBB", "labels": { TOKEN_CHECK_LABEL: "true" }
                }],
                "meta": { "pagination": { "page": 1, "per_page": 50, "previous_page": null, "next_page": null, "last_page": 1, "total_entries": 1 } }
            })))
            .mount(&server)
            .await;
        Mock::given(method("DELETE"))
            .and(path("/ssh_keys/41"))
            .respond_with(ResponseTemplate::new(204))
            .expect(1)
            .mount(&server)
            .await;

        assert_eq!(validate(&api).await, Ok(()));
    }

    #[tokio::test]
    async fn an_unknown_token_is_invalid() {
        let (_server, api) = api(
            error(401, "unauthorized", "unable to authenticate"),
            ResponseTemplate::new(204),
        )
        .await;
        assert_eq!(
            validate(&api).await,
            Err("The token is not valid, check that it was copied completely.".to_string())
        );
    }

    #[tokio::test]
    async fn a_read_only_token_is_invalid() {
        let (_server, api) = api(
            error(403, "forbidden", "insufficient permissions"),
            ResponseTemplate::new(204),
        )
        .await;
        assert_eq!(
            validate(&api).await,
            Err(
                "The token can only read, create one with the permissions read & write."
                    .to_string()
            )
        );
    }

    #[tokio::test]
    async fn the_ssh_key_left_behind_is_named() {
        let (_server, api) = api(created(), error(500, "server_error", "server error")).await;
        let error = validate(&api).await.unwrap_err();
        assert!(error.contains("sakctl-token-check-"), "{error}");
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
        assert_eq!(validate(&HetznerApi::new(&token)).await, Ok(()));
    }
}
