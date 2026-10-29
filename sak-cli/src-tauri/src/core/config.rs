//! the running configuration of a deployment, read from its backend with the admin api token
//! derived from the password: what `sakctl config show` prints

use reqwest::{Client, StatusCode};
use rust_i18n::t;
use serde::Deserialize;
use std::time::Duration;

/// what `GET /v1/config` answers: no secret, a password only as whether there is one
#[derive(Debug, Deserialize, PartialEq, Eq)]
pub struct RunningConfig {
    /// the mail server of the login links, none without the login
    pub smtp: Option<RunningSmtp>,
}

#[derive(Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RunningSmtp {
    pub host: String,
    pub port: u16,
    pub username: Option<String>,
    pub password_set: bool,
    pub from: String,
    pub tls: bool,
    pub starttls: bool,
}

/// the api of a deployment, `https://api.<base domain>`; caddy leads its `/v1` to the backend
pub fn api_url(base_domain: &str) -> String {
    format!("https://api.{base_domain}")
}

/// `GET <api>/v1/config` with the token as bearer; a refused token says the backend does not
/// know the token of this password (yet)
pub async fn fetch(api: &str, token: &str) -> Result<RunningConfig, String> {
    let client = Client::builder()
        .user_agent(concat!("sakctl/", env!("CARGO_PKG_VERSION")))
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|error| error.to_string())?;
    let url = format!("{api}/v1/config");
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

        let config = fetch(&server.uri(), "sak_token").await.unwrap();

        let smtp = config.smtp.unwrap();
        assert_eq!((smtp.host.as_str(), smtp.port), ("smtp.example.org", 465));
        assert!(smtp.password_set && smtp.tls && !smtp.starttls);
        let none = backend(200, serde_json::json!({ "smtp": null })).await;
        assert_eq!(fetch(&none.uri(), "sak_token").await.unwrap().smtp, None);
    }

    #[tokio::test]
    async fn a_refused_token_and_a_failing_backend_say_so() {
        let server = backend(200, serde_json::json!({})).await;
        // the mock knows only the right token, any other gets a 404
        assert!(fetch(&server.uri(), "sak_other").await.is_err());
        let refused = backend(401, serde_json::json!({})).await;
        assert_eq!(
            fetch(&refused.uri(), "sak_token").await,
            Err(t!("config.refused").to_string())
        );
        let failing = backend(500, serde_json::json!({})).await;
        assert!(
            fetch(&failing.uri(), "sak_token")
                .await
                .unwrap_err()
                .contains("500")
        );
        assert!(
            fetch("http://127.0.0.1:1", "sak_token")
                .await
                .unwrap_err()
                .contains("127.0.0.1:1")
        );
    }
}
