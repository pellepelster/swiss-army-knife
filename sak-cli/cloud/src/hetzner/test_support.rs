//! a mock of the hetzner api for the unit tests of the provisioners. It lists the given
//! resources and applies the name filter, so the diffs run without a cloud

use crate::api::Labels;
use crate::hetzner::api::{Backoff, HetznerApi};
use serde_json::{Value, json};
use std::sync::Arc;
use std::time::Duration;
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

/// one page with `items` under `key`, `{ "volumes": [...], "meta": {...} }`
pub fn page(key: &str, items: Vec<Value>) -> Value {
    json!({ key: items, "meta": { "pagination": { "page": 1, "per_page": 50, "next_page": null, "total_entries": items.len() } } })
}

/// a mock that lists `items` at `/<key>`: a request with a name filter gets the item of that
/// name or nothing, a request without it gets every item. Keep the server while the api is used
pub async fn mock_api(key: &str, items: Vec<Value>) -> (MockServer, Arc<HetznerApi>) {
    mock_api_all(&[(key, items)]).await
}

/// `mock_api` for several resource types at once
pub async fn mock_api_all(resources: &[(&str, Vec<Value>)]) -> (MockServer, Arc<HetznerApi>) {
    let server = MockServer::start().await;
    for (key, items) in resources {
        let endpoint = format!("/{key}");
        for item in items {
            Mock::given(method("GET"))
                .and(path(&endpoint))
                .and(query_param("name", item["name"].as_str().unwrap()))
                .respond_with(
                    ResponseTemplate::new(200).set_body_json(page(key, vec![item.clone()])),
                )
                .mount(&server)
                .await;
        }
        let any_name = |request: &Request| request.url.query_pairs().any(|(key, _)| key == "name");
        Mock::given(method("GET"))
            .and(path(&endpoint))
            .and(any_name)
            .respond_with(ResponseTemplate::new(200).set_body_json(page(key, vec![])))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path(&endpoint))
            .respond_with(ResponseTemplate::new(200).set_body_json(page(key, items.clone())))
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

pub fn labels(pairs: &[(&str, &str)]) -> Labels {
    pairs
        .iter()
        .map(|(key, value)| (key.to_string(), value.to_string()))
        .collect()
}
