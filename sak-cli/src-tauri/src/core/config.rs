//! the running configuration of a deployment, read from its backend with the admin api token
//! derived from the password: what `sakctl config show` prints

use reqwest::redirect::Policy;
use reqwest::{Client, StatusCode};
use rust_i18n::t;
use serde::{Deserialize, Serialize};
use std::time::Duration;

/// what `GET /v1/config` answers: no secret, a password only as whether there is one; the page
/// takes the mail server of a redeploy from it
#[derive(Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct RunningConfig {
    /// the mail server of the login links, none without the login
    pub smtp: Option<RunningSmtp>,
    /// the addresses of the e-mail login, the first the admin; a backend of before answers none
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
    /// the password at the mail server, a redeploy takes it over; never printed
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

/// `GET <api>/v1/config` with the token as bearer; a refused token says the backend does not
/// know the token of this password (yet). The token goes over https only and to the api alone:
/// a plain http api is refused before any request, a redirect is not followed but fails
pub async fn fetch(api: &str, token: &str, trust: Trust) -> Result<RunningConfig, String> {
    fetch_from(api, token, Transport::HttpsOnly, trust).await
}

/// whose certificates a deployment has: of a public ca, or of the staging ca of let's encrypt,
/// whose roots are trusted then as well (a deployment of the tests)
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

/// whether plain http is refused; only the tests talk to their mock backend over http
#[derive(Clone, Copy, PartialEq, Eq)]
enum Transport {
    HttpsOnly,
    #[cfg(test)]
    PlainHttp,
}

async fn fetch_from(
    api: &str,
    token: &str,
    transport: Transport,
    trust: Trust,
) -> Result<RunningConfig, String> {
    let url = format!("{api}/v1/config");
    let https_only = transport == Transport::HttpsOnly;
    if https_only && !api.starts_with("https://") {
        return Err(t!("config.not_https", url = api).to_string());
    }
    let response = client(https_only, trust)?
        .get(&url)
        .bearer_auth(token)
        .send()
        .await
        .map_err(|error| {
            t!("config.unreachable", url = url, error = with_causes(&error)).to_string()
        })?;
    match response.status() {
        status if status.is_success() => response
            .json::<RunningConfig>()
            .await
            .map_err(|error| t!("config.unreadable", error = error.to_string()).to_string()),
        StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN => Err(t!("config.refused").to_string()),
        // a redirect too: the token goes to the api and nowhere else
        status => Err(t!("config.failed", url = url, status = status.to_string()).to_string()),
    }
}

/// the client of the api of a deployment: no redirect followed, plain http only for the tests;
/// the roots of the staging ca of let's encrypt trusted for a deployment with its certificates
fn client(https_only: bool, trust: Trust) -> Result<Client, String> {
    client_builder(https_only, trust)?
        .build()
        .map_err(|error| error.to_string())
}

fn client_builder(https_only: bool, trust: Trust) -> Result<reqwest::ClientBuilder, String> {
    let mut builder = Client::builder()
        .user_agent(concat!("sakctl/", env!("CARGO_PKG_VERSION")))
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

/// an error with every cause under it, so a failed request says why it failed (no name, no
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

/// how long a new deployment has to answer at its public endpoint, its certificate included
pub const API_WAIT: Duration = Duration::from_secs(300);

/// waits until `GET <api>/v1/info`, the endpoint of a deployment that needs no login, answers:
/// through its ingress, with its certificate, the way a user reaches it. The host of the api is
/// reached at `address`, the server just provisioned: a resolver of this machine may still know
/// the address of a server the name had before. Asked every `interval`; after `timeout` the cause
/// of the last failure
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
                "config.api_not_answering",
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
    use wiremock::matchers::{header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    async fn backend(status: u16, body: serde_json::Value) -> MockServer {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v1/config"))
            .and(header("authorization", "Bearer sak_token"))
            .respond_with(ResponseTemplate::new(status).set_body_json(body))
            .mount(&server)
            .await;
        server
    }

    #[test]
    fn the_api_is_api_below_the_base_domain() {
        assert_eq!(api_url("alpha.pelle.io"), "https://api.alpha.pelle.io");
    }

    #[tokio::test]
    async fn the_config_is_read_with_the_token_as_bearer() {
        let server = backend(
            200,
            serde_json::json!({ "smtp": {
                "host": "smtp.example.org", "port": 465, "username": "user", "passwordSet": true,
                "from": "noreply@example.org", "tls": true, "starttls": false
            } }),
        )
        .await;

        let config = fetch_from(
            &server.uri(),
            "sak_token",
            Transport::PlainHttp,
            Trust::Public,
        )
        .await
        .unwrap();

        let smtp = config.smtp.unwrap();
        assert_eq!((smtp.host.as_str(), smtp.port), ("smtp.example.org", 465));
        assert!(smtp.password_set && smtp.tls && !smtp.starttls);
        let none = backend(200, serde_json::json!({ "smtp": null })).await;
        assert_eq!(
            fetch_from(
                &none.uri(),
                "sak_token",
                Transport::PlainHttp,
                Trust::Public
            )
            .await
            .unwrap()
            .smtp,
            None
        );
    }

    #[tokio::test]
    async fn a_refused_token_and_a_failing_backend_say_so() {
        let server = backend(200, serde_json::json!({})).await;
        // the mock knows only the right token, any other gets a 404
        assert!(
            fetch_from(
                &server.uri(),
                "sak_other",
                Transport::PlainHttp,
                Trust::Public
            )
            .await
            .is_err()
        );
        let refused = backend(401, serde_json::json!({})).await;
        assert_eq!(
            fetch_from(
                &refused.uri(),
                "sak_token",
                Transport::PlainHttp,
                Trust::Public
            )
            .await,
            Err(t!("config.refused").to_string())
        );
        let failing = backend(500, serde_json::json!({})).await;
        assert!(
            fetch_from(
                &failing.uri(),
                "sak_token",
                Transport::PlainHttp,
                Trust::Public
            )
            .await
            .unwrap_err()
            .contains("500")
        );
        assert!(
            fetch_from(
                "http://127.0.0.1:1",
                "sak_token",
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
                "sak_token",
                Transport::PlainHttp,
                Trust::Public
            )
            .await,
            Err(t!("config.refused").to_string())
        );
    }

    #[tokio::test]
    async fn a_plain_http_api_is_refused_before_any_request() {
        let server = backend(200, serde_json::json!({ "smtp": null })).await;

        assert_eq!(
            fetch(&server.uri(), "sak_token", Trust::Public).await,
            Err(t!("config.not_https", url = server.uri()).to_string())
        );
        assert!(server.received_requests().await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn a_redirect_is_not_followed_and_the_token_goes_nowhere_else() {
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

        let error = fetch_from(&api.uri(), "sak_token", Transport::PlainHttp, Trust::Public)
            .await
            .unwrap_err();

        assert_eq!(
            error,
            t!(
                "config.failed",
                url = format!("{}/v1/config", api.uri()),
                status = "302 Found"
            )
            .to_string()
        );
        assert!(elsewhere.received_requests().await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn the_errors_carry_no_token() {
        let failing = backend(500, serde_json::json!({})).await;
        let unreadable = backend(200, serde_json::json!({ "smtp": "no config" })).await;
        let errors = [
            fetch_from(
                "http://127.0.0.1:1",
                "sak_token",
                Transport::PlainHttp,
                Trust::Public,
            )
            .await,
            fetch_from(
                &failing.uri(),
                "sak_token",
                Transport::PlainHttp,
                Trust::Public,
            )
            .await,
            fetch_from(
                &unreadable.uri(),
                "sak_token",
                Transport::PlainHttp,
                Trust::Public,
            )
            .await,
            fetch("http://api.example.org", "sak_token", Trust::Public).await,
        ];
        for error in errors {
            let error = error.unwrap_err();
            assert!(!error.contains("sak_token"), "{error}");
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
