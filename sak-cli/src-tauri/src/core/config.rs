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

#[derive(Debug, Deserialize, Serialize, PartialEq, Eq)]
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

/// the api of a deployment, `https://api.<base domain>`; caddy leads its `/v1` to the backend
pub fn api_url(base_domain: &str) -> String {
    format!("https://api.{base_domain}")
}

/// `GET <api>/v1/config` with the token as bearer; a refused token says the backend does not
/// know the token of this password (yet). The token goes over https only and to the api alone:
/// a plain http api is refused before any request, a redirect is not followed but fails
pub async fn fetch(api: &str, token: &str) -> Result<RunningConfig, String> {
    fetch_from(api, token, Transport::HttpsOnly).await
}

/// whether plain http is refused; only the tests talk to their mock backend over http
#[derive(Clone, Copy, PartialEq, Eq)]
enum Transport {
    HttpsOnly,
    #[cfg(test)]
    PlainHttp,
}

async fn fetch_from(api: &str, token: &str, transport: Transport) -> Result<RunningConfig, String> {
    let url = format!("{api}/v1/config");
    let https_only = transport == Transport::HttpsOnly;
    if https_only && !api.starts_with("https://") {
        return Err(t!("config.not_https", url = api).to_string());
    }
    let client = Client::builder()
        .user_agent(concat!("sakctl/", env!("CARGO_PKG_VERSION")))
        .timeout(Duration::from_secs(30))
        .redirect(Policy::none())
        .https_only(https_only)
        .build()
        .map_err(|error| error.to_string())?;
    let response = client
        .get(&url)
        .bearer_auth(token)
        .send()
        .await
        .map_err(|error| {
            t!("config.unreachable", url = url, error = error.to_string()).to_string()
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

        let config = fetch_from(&server.uri(), "sak_token", Transport::PlainHttp)
            .await
            .unwrap();

        let smtp = config.smtp.unwrap();
        assert_eq!((smtp.host.as_str(), smtp.port), ("smtp.example.org", 465));
        assert!(smtp.password_set && smtp.tls && !smtp.starttls);
        let none = backend(200, serde_json::json!({ "smtp": null })).await;
        assert_eq!(
            fetch_from(&none.uri(), "sak_token", Transport::PlainHttp)
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
            fetch_from(&server.uri(), "sak_other", Transport::PlainHttp)
                .await
                .is_err()
        );
        let refused = backend(401, serde_json::json!({})).await;
        assert_eq!(
            fetch_from(&refused.uri(), "sak_token", Transport::PlainHttp).await,
            Err(t!("config.refused").to_string())
        );
        let failing = backend(500, serde_json::json!({})).await;
        assert!(
            fetch_from(&failing.uri(), "sak_token", Transport::PlainHttp)
                .await
                .unwrap_err()
                .contains("500")
        );
        assert!(
            fetch_from("http://127.0.0.1:1", "sak_token", Transport::PlainHttp)
                .await
                .unwrap_err()
                .contains("127.0.0.1:1")
        );
    }

    #[tokio::test]
    async fn a_forbidden_answer_is_refused_like_an_unauthorized_one() {
        let forbidden = backend(403, serde_json::json!({})).await;
        assert_eq!(
            fetch_from(&forbidden.uri(), "sak_token", Transport::PlainHttp).await,
            Err(t!("config.refused").to_string())
        );
    }

    #[tokio::test]
    async fn a_plain_http_api_is_refused_before_any_request() {
        let server = backend(200, serde_json::json!({ "smtp": null })).await;

        assert_eq!(
            fetch(&server.uri(), "sak_token").await,
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

        let error = fetch_from(&api.uri(), "sak_token", Transport::PlainHttp)
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
            fetch_from("http://127.0.0.1:1", "sak_token", Transport::PlainHttp).await,
            fetch_from(&failing.uri(), "sak_token", Transport::PlainHttp).await,
            fetch_from(&unreadable.uri(), "sak_token", Transport::PlainHttp).await,
            fetch("http://api.example.org", "sak_token").await,
        ];
        for error in errors {
            let error = error.unwrap_err();
            assert!(!error.contains("sak_token"), "{error}");
        }
    }
}
