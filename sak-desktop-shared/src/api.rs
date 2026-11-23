//! a call of the api of the SAK instance with the session of the app, for the pages of a desktop
//! app: the body and the answer as json, the errors as the api gives them (API-1), in the language
//! of the app

use crate::session::SessionManager;
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// one error of the api: its code and its text in the language of the app
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ApiErrorMessage {
    pub code: String,
    pub message: String,
}

/// why a call gave no answer, as the page shows it
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ApiError {
    /// no session, or the instance no longer takes it: the session is forgotten, the page shows the
    /// sign-in
    SignedOut,
    /// the instance refused the call with the errors of the api, e.g. of a field
    Refused {
        status: u16,
        errors: Vec<ApiErrorMessage>,
    },
    /// the instance did not answer, or not as expected
    Failed { message: String },
}

#[derive(Deserialize)]
struct ApiErrorResponse {
    errors: Vec<ApiErrorMessage>,
}

/// `method` of `path` below the api (e.g. `examples`) with `body`; an answer without a
/// body is null
pub async fn call(
    sessions: &SessionManager,
    method: reqwest::Method,
    path: &str,
    body: Option<&Value>,
) -> Result<Value, ApiError> {
    let Some(request) = sessions.request(method, path) else {
        return Err(ApiError::SignedOut);
    };
    let request = request.header(
        reqwest::header::ACCEPT_LANGUAGE,
        rust_i18n::locale().to_string(),
    );
    let request = match body {
        Some(body) => request.json(body),
        None => request,
    };
    let response = request.send().await.map_err(failed)?;
    let status = response.status();
    if status == reqwest::StatusCode::UNAUTHORIZED {
        sessions.logout().await;
        return Err(ApiError::SignedOut);
    }
    let text = response.text().await.map_err(failed)?;
    if !status.is_success() {
        let errors = serde_json::from_str::<ApiErrorResponse>(&text)
            .map(|answer| answer.errors)
            .unwrap_or_default();
        return Err(ApiError::Refused {
            status: status.as_u16(),
            errors,
        });
    }
    if text.is_empty() {
        return Ok(Value::Null);
    }
    serde_json::from_str(&text).map_err(|error| ApiError::Failed {
        message: error.to_string(),
    })
}

/// every item of a list of the api (API-2), page by page
pub async fn all(sessions: &SessionManager, path: &str) -> Result<Vec<Value>, ApiError> {
    const LIMIT: u64 = 100;
    let mut items = Vec::new();
    loop {
        let separator = if path.contains('?') { '&' } else { '?' };
        let page = call(
            sessions,
            reqwest::Method::GET,
            &format!("{path}{separator}offset={}&limit={LIMIT}", items.len()),
            None,
        )
        .await?;
        let got = page["items"].as_array().cloned().unwrap_or_default();
        let total = page["pagination"]["total"].as_u64().unwrap_or(0);
        let empty = got.is_empty();
        items.extend(got);
        if empty || items.len() as u64 >= total {
            return Ok(items);
        }
    }
}

/// with its causes, so the user reads why the request failed (no name, no certificate, refused);
/// without the url, it names only the address the user typed anyway
fn failed(error: reqwest::Error) -> ApiError {
    ApiError::Failed {
        message: crate::errors::with_causes(&error.without_url()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::secrets::Memory;
    use crate::session::{Address, LOGIN_TIMEOUT};
    use std::sync::Arc;
    use wiremock::matchers::{header, method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    /// a session at a mock instance, taken up from the keychain of the tests
    async fn signed_in() -> (SessionManager, MockServer) {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/.well-known/sak.json"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(
                    serde_json::json!({ "api": format!("{}/api/v1", server.uri()) }),
                ),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/api/v1/auth/who-am-i"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "username": "anna@example.org", "name": null, "expiresAt": null, "roles": ["user"]
            })))
            .mount(&server)
            .await;
        let address = Address::parse_with(&server.uri(), true).unwrap();
        let secrets = Arc::new(Memory::default());
        secrets
            .tokens
            .lock()
            .unwrap()
            .insert(address.as_str().to_string(), "token".to_string());
        let sessions = SessionManager::new(secrets, LOGIN_TIMEOUT);
        sessions
            .restore(address, Some(format!("{}/api/v1/", server.uri())))
            .await
            .unwrap()
            .unwrap();
        (sessions, server)
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_call_sends_the_session_and_the_language_and_answers_the_json() {
        let (sessions, server) = signed_in().await;
        Mock::given(method("POST"))
            .and(path("/api/v1/examples"))
            .and(header("authorization", "Bearer token"))
            .respond_with(ResponseTemplate::new(201).set_body_json(serde_json::json!({ "id": 7 })))
            .mount(&server)
            .await;
        let answer = call(
            &sessions,
            reqwest::Method::POST,
            "examples",
            Some(&serde_json::json!({ "name": "x" })),
        )
        .await;
        assert_eq!(answer, Ok(serde_json::json!({ "id": 7 })));
        let sent = server.received_requests().await.unwrap();
        let post = sent
            .iter()
            .find(|request| request.method == wiremock::http::Method::POST)
            .unwrap();
        assert!(post.headers.get("accept-language").is_some());
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_refusal_has_the_errors_of_the_api() {
        let (sessions, server) = signed_in().await;
        Mock::given(method("POST"))
            .and(path("/api/v1/examples"))
            .respond_with(ResponseTemplate::new(400).set_body_json(serde_json::json!({
                "errors": [{ "code": "examples.invalid_name", "message": "A name has…" }]
            })))
            .mount(&server)
            .await;
        let answer = call(
            &sessions,
            reqwest::Method::POST,
            "examples",
            Some(&Value::Null),
        )
        .await;
        assert_eq!(
            answer,
            Err(ApiError::Refused {
                status: 400,
                errors: vec![ApiErrorMessage {
                    code: "examples.invalid_name".to_string(),
                    message: "A name has…".to_string()
                }]
            })
        );
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_session_the_instance_no_longer_takes_signs_out() {
        let (sessions, server) = signed_in().await;
        Mock::given(method("GET"))
            .and(path("/api/v1/examples"))
            .respond_with(ResponseTemplate::new(401))
            .mount(&server)
            .await;
        assert_eq!(
            call(&sessions, reqwest::Method::GET, "examples", None).await,
            Err(ApiError::SignedOut)
        );
        assert_eq!(sessions.user(), None);
        assert_eq!(
            call(&sessions, reqwest::Method::GET, "examples", None).await,
            Err(ApiError::SignedOut)
        );
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn all_reads_every_page() {
        let (sessions, server) = signed_in().await;
        for (offset, items) in [("0", vec![1, 2]), ("2", vec![3])] {
            Mock::given(method("GET"))
                .and(path("/api/v1/examples"))
                .and(query_param("offset", offset))
                .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                    "items": items, "pagination": { "offset": 0, "limit": 100, "total": 3 }
                })))
                .mount(&server)
                .await;
        }
        let items = all(&sessions, "examples").await.unwrap();
        assert_eq!(
            items,
            vec![
                serde_json::json!(1),
                serde_json::json!(2),
                serde_json::json!(3)
            ]
        );
    }
}
