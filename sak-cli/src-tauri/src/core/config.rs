//! the running configuration of a deployment, as `sakctl config show` prints it. sakctl reads it
//! from the backend with a request signed with the api key (`keys::api_key`). No request carries
//! a secret. The signature covers the method, the host, the path, the time, a nonce and the body,
//! so the backend refuses a request sent to a wrong host or sent again

use crate::core::keys::ApiKey;
use reqwest::redirect::Policy;
use reqwest::{Client, StatusCode};
use rust_i18n::t;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::time::Duration;

/// what `GET /v1/config` answers. It holds the password of the mail server, because a redeploy
/// takes the mail server over from it (`deployments::detect`)
#[derive(Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct RunningConfig {
    /// the mail server of the login links, none without the login
    pub smtp: Option<RunningSmtp>,
    /// the addresses of the e-mail login, the first is the admin; an older backend answers none
    #[serde(default)]
    pub admins: Vec<String>,
}

/// no derived Debug, the password never shows
#[derive(Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RunningSmtp {
    pub host: String,
    pub port: u16,
    pub username: Option<String>,
    pub password_set: bool,
    /// the password at the mail server, a redeploy takes it over; never printed. An older backend
    /// answers none
    #[serde(default)]
    pub password: Option<String>,
    pub from: String,
    pub tls: bool,
    pub starttls: bool,
}

impl std::fmt::Debug for RunningSmtp {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RunningSmtp")
            .field("host", &self.host)
            .field("port", &self.port)
            .field("username", &self.username)
            .field("password_set", &self.password_set)
            .field("password", &self.password.as_ref().map(|_| "***"))
            .field("from", &self.from)
            .field("tls", &self.tls)
            .field("starttls", &self.starttls)
            .finish()
    }
}

/// the api of a deployment, `https://api.<base domain>`; caddy leads its `/v1` to the backend
pub fn api_url(base_domain: &str) -> String {
    format!("https://api.{base_domain}")
}

/// `GET <api>/v1/config`, signed with the api key. A refused signature means that the backend
/// does not know the key of this password (yet). Over https only and to the api only: sakctl
/// refuses a plain http api before any request, and a redirect fails
pub async fn fetch(api: &str, key: &ApiKey, trust: Trust) -> Result<RunningConfig, String> {
    fetch_from(api, key, Transport::HttpsOnly, trust).await
}

/// the scheme of the `Authorization` header of a signed request
pub const SIGNATURE_SCHEME: &str = "SAK-Ed25519";

/// the text sakctl signs for a request to the admin api, one part on each line: the version of the
/// format, the method, the host in lower case without the port, the path with its query, the time
/// in unix seconds, the nonce and the sha-256 of the body as hex. The backend builds the same text
/// with its own host
pub fn signed_text(
    method: &str,
    host: &str,
    path_and_query: &str,
    timestamp: u64,
    nonce: &str,
    body: &[u8],
) -> String {
    format!(
        "sak-api-v1\n{method}\n{}\n{path_and_query}\n{timestamp}\n{nonce}\n{}",
        host.to_ascii_lowercase(),
        Sha256::digest(body)
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>()
    )
}

/// `SAK-Ed25519 ts=<unix seconds>,nonce=<hex>,sig=<base64>` of a request without a body
fn authorization(key: &ApiKey, method: &str, url: &reqwest::Url) -> Result<String, String> {
    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|error| error.to_string())?
        .as_secs();
    let mut nonce = [0u8; 16];
    getrandom::fill(&mut nonce).map_err(|error| error.to_string())?;
    let nonce: String = nonce.iter().map(|byte| format!("{byte:02x}")).collect();
    let path_and_query = match url.query() {
        Some(query) => format!("{}?{query}", url.path()),
        None => url.path().to_string(),
    };
    let text = signed_text(
        method,
        url.host_str().unwrap_or_default(),
        &path_and_query,
        timestamp,
        &nonce,
        b"",
    );
    Ok(format!(
        "{SIGNATURE_SCHEME} ts={timestamp},nonce={nonce},sig={}",
        key.sign(text.as_bytes())
    ))
}

/// where the certificates of a deployment come from: a public ca, or the staging ca of let's
/// encrypt (a deployment of the tests), whose roots sakctl then trusts too
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Trust {
    Public,
    LetsEncryptStaging,
}

/// the roots of the staging ca of let's encrypt, `(STAGING) Pretend Pear X1` and `(STAGING) Bogus
/// Broccoli X2`, as letsencrypt.org/certs/staging serves them
const STAGING_ROOTS: [&str; 2] = [
    include_str!("../../certs/letsencrypt-stg-root-x1.pem"),
    include_str!("../../certs/letsencrypt-stg-root-x2.pem"),
];

/// whether sakctl refuses plain http; only the tests talk to their mock backend over http
#[derive(Clone, Copy, PartialEq, Eq)]
enum Transport {
    HttpsOnly,
    #[cfg(test)]
    PlainHttp,
}

async fn fetch_from(
    api: &str,
    key: &ApiKey,
    transport: Transport,
    trust: Trust,
) -> Result<RunningConfig, String> {
    let url = format!("{api}/v1/config");
    let https_only = transport == Transport::HttpsOnly;
    if https_only && !api.starts_with("https://") {
        return Err(t!("shared.config.not_https", url = api).to_string());
    }
    let parsed = reqwest::Url::parse(&url).map_err(|error| error.to_string())?;
    let response = client(https_only, trust)?
        .get(&url)
        .header(
            reqwest::header::AUTHORIZATION,
            authorization(key, "GET", &parsed)?,
        )
        .send()
        .await
        .map_err(|error| {
            t!(
                "shared.config.unreachable",
                url = url,
                error = with_causes(&error)
            )
            .to_string()
        })?;
    match response.status() {
        status if status.is_success() => response
            .json::<RunningConfig>()
            .await
            .map_err(|error| t!("shared.config.unreadable", error = error.to_string()).to_string()),
        StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN => {
            Err(t!("shared.config.refused").to_string())
        }
        // also a redirect: the request goes to the api only
        status => Err(t!(
            "shared.config.failed",
            url = url,
            status = status.to_string()
        )
        .to_string()),
    }
}

/// the client of the api of a deployment: it follows no redirect, plain http only for the tests.
/// With `Trust::LetsEncryptStaging` it also trusts the roots of the staging ca
fn client(https_only: bool, trust: Trust) -> Result<Client, String> {
    client_builder(https_only, trust)?
        .build()
        .map_err(|error| error.to_string())
}

fn client_builder(https_only: bool, trust: Trust) -> Result<reqwest::ClientBuilder, String> {
    let mut builder = Client::builder()
        .user_agent(concat!("sakctl/", env!("SAK_VERSION")))
        .timeout(Duration::from_secs(30))
        .redirect(Policy::none())
        .https_only(https_only);
    if trust == Trust::LetsEncryptStaging {
        for root in STAGING_ROOTS {
            builder = builder.add_root_certificate(
                reqwest::Certificate::from_pem(root.as_bytes())
                    .map_err(|error| error.to_string())?,
            );
        }
    }
    Ok(builder)
}

/// an error with all its causes, so a failed request tells why it failed (no name, no
/// connection, no certificate): `error sending request: … : invalid peer certificate`
pub fn with_causes(error: &dyn std::error::Error) -> String {
    let mut text = error.to_string();
    let mut source = error.source();
    while let Some(cause) = source {
        let cause_text = cause.to_string();
        if !text.contains(&cause_text) {
            text.push_str(": ");
            text.push_str(&cause_text);
        }
        source = cause.source();
    }
    text
}

/// the time a new deployment has to answer at its public endpoint, with its certificate
pub const API_WAIT: Duration = Duration::from_secs(300);

/// waits until `GET <api>/v1/info` (no login needed) answers through the ingress with the
/// certificate, as a user reaches it. The request goes to `address`, the server just provisioned,
/// because a resolver of this machine can still know the address of an earlier server of the
/// name. Asks every `interval`; after `timeout` the error tells the cause of the last failure
pub async fn wait_for_api(
    api: &str,
    address: Option<std::net::IpAddr>,
    trust: Trust,
    timeout: Duration,
    interval: Duration,
) -> Result<(), String> {
    wait_from(api, address, timeout, interval, Transport::HttpsOnly, trust).await
}

async fn wait_from(
    api: &str,
    address: Option<std::net::IpAddr>,
    timeout: Duration,
    interval: Duration,
    transport: Transport,
    trust: Trust,
) -> Result<(), String> {
    let url = format!("{api}/v1/info");
    let mut builder = client_builder(transport == Transport::HttpsOnly, trust)?;
    if let Some(address) = address
        && let Ok(parsed) = url::Url::parse(&url)
        && let (Some(host), Some(port)) = (parsed.host_str(), parsed.port_or_known_default())
    {
        builder = builder.resolve(host, std::net::SocketAddr::new(address, port));
    }
    let client = builder.build().map_err(|error| error.to_string())?;
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        let last = match client.get(&url).send().await {
            Ok(response) if response.status().is_success() => return Ok(()),
            Ok(response) => response.status().to_string(),
            Err(error) => with_causes(&error),
        };
        if tokio::time::Instant::now() + interval > deadline {
            return Err(t!(
                "shared.config.api_not_answering",
                url = url,
                seconds = timeout.as_secs(),
                error = last
            )
            .to_string());
        }
        tokio::time::sleep(interval).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::keys::api_key;
    use base64::Engine;
    use base64::engine::general_purpose::STANDARD;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, Request, ResponseTemplate};

    fn key() -> ApiKey {
        api_key("alpha", "a password of sixteen").unwrap()
    }

    /// whether the request is signed with the key of `alpha` for its host, its path, the time now
    /// and a nonce, as the backend checks it
    fn signed_by_alpha(request: &Request) -> bool {
        use ed25519_dalek::Verifier;
        let Some(value) = request
            .headers
            .get("authorization")
            .and_then(|value| value.to_str().ok())
        else {
            return false;
        };
        let Some(fields) = value.strip_prefix("SAK-Ed25519 ") else {
            return false;
        };
        let fields: std::collections::HashMap<&str, &str> = fields
            .split(',')
            .filter_map(|field| field.split_once('='))
            .collect();
        let (Some(ts), Some(nonce), Some(sig)) = (
            fields.get("ts").and_then(|ts| ts.parse::<u64>().ok()),
            fields.get("nonce"),
            fields.get("sig"),
        ) else {
            return false;
        };
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        let text = signed_text(
            request.method.as_str(),
            // the host the request went to, without its port; wiremock's url names localhost
            request
                .headers
                .get("host")
                .and_then(|host| host.to_str().ok())
                .and_then(|host| host.split(':').next())
                .unwrap_or_default(),
            request.url.path(),
            ts,
            nonce,
            &request.body,
        );
        let public: [u8; 32] = STANDARD
            .decode(key().public_key())
            .unwrap()
            .try_into()
            .unwrap();
        let Ok(signature) = STANDARD.decode(sig) else {
            return false;
        };
        let Ok(signature) = ed25519_dalek::Signature::from_slice(&signature) else {
            return false;
        };
        nonce.len() == 32
            && now.abs_diff(ts) < 5
            && ed25519_dalek::VerifyingKey::from_bytes(&public)
                .unwrap()
                .verify(text.as_bytes(), &signature)
                .is_ok()
    }

    async fn backend(status: u16, body: serde_json::Value) -> MockServer {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v1/config"))
            .and(signed_by_alpha)
            .respond_with(ResponseTemplate::new(status).set_body_json(body))
            .mount(&server)
            .await;
        server
    }

    /// a request of `alpha` signed for a fixed time and nonce: the same constants are in the tests
    /// of the backend (`ApiSignatureTest`), which checks the signature with `API_PUBLIC_KEY_VECTOR`
    /// of core/keys.rs
    pub const SIGNATURE_VECTOR: (&str, &str, &str, u64, &str, &str) = (
        "GET",
        "api.example.org",
        "/v1/config",
        1_767_225_600,
        "00112233445566778899aabbccddeeff",
        "KUot+EGbCGfsihgrwiN9+XeBDYbp37EILgeTXouu4c2N8zpExVRCLfivPOmrLm+Ty97/KGlKzO/99SqWuJbjCA==",
    );

    #[test]
    fn the_signature_is_pinned() {
        let (method, host, path, ts, nonce, signature) = SIGNATURE_VECTOR;
        let text = signed_text(method, host, path, ts, nonce, b"");
        assert_eq!(
            text,
            "sak-api-v1\nGET\napi.example.org\n/v1/config\n1767225600\n00112233445566778899aabbccddeeff\n\
             e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(key().sign(text.as_bytes()), signature);
        // the host counts in lower case, without its port
        assert_eq!(
            signed_text(method, "API.Example.org", path, ts, nonce, b""),
            text
        );
    }

    #[test]
    fn each_request_has_a_nonce_of_its_own() {
        let url = reqwest::Url::parse("https://api.example.org/v1/config").unwrap();
        let first = authorization(&key(), "GET", &url).unwrap();
        let second = authorization(&key(), "GET", &url).unwrap();
        assert!(first.starts_with("SAK-Ed25519 ts="));
        assert_ne!(first, second);
    }

    #[test]
    fn the_api_is_api_below_the_base_domain() {
        assert_eq!(api_url("alpha.pelle.io"), "https://api.alpha.pelle.io");
    }

    #[tokio::test]
    async fn the_config_is_read_with_a_signed_request() {
        let server = backend(
            200,
            serde_json::json!({ "smtp": {
                "host": "smtp.example.org", "port": 465, "username": "user", "passwordSet": true,
                "from": "noreply@example.org", "tls": true, "starttls": false
            } }),
        )
        .await;

        let config = fetch_from(&server.uri(), &key(), Transport::PlainHttp, Trust::Public)
            .await
            .unwrap();

        let smtp = config.smtp.unwrap();
        assert_eq!((smtp.host.as_str(), smtp.port), ("smtp.example.org", 465));
        assert!(smtp.password_set && smtp.tls && !smtp.starttls);
        let none = backend(200, serde_json::json!({ "smtp": null })).await;
        assert_eq!(
            fetch_from(&none.uri(), &key(), Transport::PlainHttp, Trust::Public)
                .await
                .unwrap()
                .smtp,
            None
        );
    }

    #[tokio::test]
    async fn a_refused_signature_and_a_failing_backend_say_so() {
        let server = backend(200, serde_json::json!({})).await;
        // the mock knows only the key of alpha, another key gets a 404
        assert!(
            fetch_from(
                &server.uri(),
                &api_key("beta", "a password of sixteen").unwrap(),
                Transport::PlainHttp,
                Trust::Public
            )
            .await
            .is_err()
        );
        let refused = backend(401, serde_json::json!({})).await;
        assert_eq!(
            fetch_from(&refused.uri(), &key(), Transport::PlainHttp, Trust::Public).await,
            Err(t!("shared.config.refused").to_string())
        );
        let failing = backend(500, serde_json::json!({})).await;
        assert!(
            fetch_from(&failing.uri(), &key(), Transport::PlainHttp, Trust::Public)
                .await
                .unwrap_err()
                .contains("500")
        );
        assert!(
            fetch_from(
                "http://127.0.0.1:1",
                &key(),
                Transport::PlainHttp,
                Trust::Public
            )
            .await
            .unwrap_err()
            .contains("127.0.0.1:1")
        );
    }

    #[tokio::test]
    async fn a_forbidden_answer_is_refused_like_an_unauthorized_one() {
        let forbidden = backend(403, serde_json::json!({})).await;
        assert_eq!(
            fetch_from(
                &forbidden.uri(),
                &key(),
                Transport::PlainHttp,
                Trust::Public
            )
            .await,
            Err(t!("shared.config.refused").to_string())
        );
    }

    #[tokio::test]
    async fn a_plain_http_api_is_refused_before_any_request() {
        let server = backend(200, serde_json::json!({ "smtp": null })).await;

        assert_eq!(
            fetch(&server.uri(), &key(), Trust::Public).await,
            Err(t!("shared.config.not_https", url = server.uri()).to_string())
        );
        assert!(server.received_requests().await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn a_redirect_is_not_followed_and_the_request_goes_nowhere_else() {
        let elsewhere = MockServer::start().await;
        Mock::given(method("GET"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(serde_json::json!({ "smtp": null })),
            )
            .mount(&elsewhere)
            .await;
        let api = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v1/config"))
            .respond_with(
                ResponseTemplate::new(302)
                    .insert_header("location", format!("{}/v1/config", elsewhere.uri())),
            )
            .mount(&api)
            .await;

        let error = fetch_from(&api.uri(), &key(), Transport::PlainHttp, Trust::Public)
            .await
            .unwrap_err();

        assert_eq!(
            error,
            t!(
                "shared.config.failed",
                url = format!("{}/v1/config", api.uri()),
                status = "302 Found"
            )
            .to_string()
        );
        assert!(elsewhere.received_requests().await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn the_errors_carry_no_signature() {
        let failing = backend(500, serde_json::json!({})).await;
        let unreadable = backend(200, serde_json::json!({ "smtp": "no config" })).await;
        let errors = [
            fetch_from(
                "http://127.0.0.1:1",
                &key(),
                Transport::PlainHttp,
                Trust::Public,
            )
            .await,
            fetch_from(&failing.uri(), &key(), Transport::PlainHttp, Trust::Public).await,
            fetch_from(
                &unreadable.uri(),
                &key(),
                Transport::PlainHttp,
                Trust::Public,
            )
            .await,
            fetch("http://api.example.org", &key(), Trust::Public).await,
        ];
        for error in errors {
            let error = error.unwrap_err();
            assert!(!error.contains("sig="), "{error}");
        }
    }

    #[tokio::test]
    async fn the_wait_ends_when_the_public_endpoint_answers() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v1/info"))
            .respond_with(ResponseTemplate::new(503))
            .up_to_n_times(2)
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/v1/info"))
            .respond_with(ResponseTemplate::new(200))
            .mount(&server)
            .await;

        let waited = wait_from(
            &server.uri(),
            None,
            Duration::from_secs(5),
            Duration::from_millis(10),
            Transport::PlainHttp,
            Trust::Public,
        )
        .await;

        assert_eq!(waited, Ok(()));
        assert_eq!(server.received_requests().await.unwrap().len(), 3);
    }

    #[tokio::test]
    async fn the_wait_gives_up_with_the_cause() {
        let error = wait_from(
            "http://127.0.0.1:1",
            None,
            Duration::from_millis(50),
            Duration::from_millis(10),
            Transport::PlainHttp,
            Trust::Public,
        )
        .await
        .unwrap_err();

        assert!(error.contains("http://127.0.0.1:1/v1/info"), "{error}");
        // the cause under the request error: the connection was refused
        assert!(error.to_lowercase().contains("connect"), "{error}");
    }

    #[test]
    fn the_roots_of_the_staging_ca_are_certificates() {
        for root in STAGING_ROOTS {
            assert!(root.contains("BEGIN CERTIFICATE"));
            assert!(reqwest::Certificate::from_pem(root.as_bytes()).is_ok());
        }
        assert!(client(true, Trust::LetsEncryptStaging).is_ok());
    }

    #[tokio::test]
    async fn the_wait_reaches_the_host_at_the_address_of_the_server() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v1/info"))
            .respond_with(ResponseTemplate::new(200))
            .mount(&server)
            .await;
        let port = server.address().port();

        // a name no resolver knows, reached at the address given
        let waited = wait_from(
            &format!("http://api.sak-test.invalid:{port}"),
            Some(std::net::IpAddr::from([127, 0, 0, 1])),
            Duration::from_secs(5),
            Duration::from_millis(10),
            Transport::PlainHttp,
            Trust::Public,
        )
        .await;

        assert_eq!(waited, Ok(()));
        let request = &server.received_requests().await.unwrap()[0];
        assert_eq!(
            request.headers.get("host").unwrap(),
            &format!("api.sak-test.invalid:{port}")
        );
    }
}
