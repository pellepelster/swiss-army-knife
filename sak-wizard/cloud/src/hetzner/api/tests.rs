//! the client against a mock of the api: paths, bodies and the token of the requests,
//! pagination, the rate limit retry, action polling and the errors

use super::primary_ips::{AssigneeType, PrimaryIpCreate, PrimaryIpType};
use super::servers::{PublicNetCreate, ServerCreate};
use super::ssh_keys::SshKeyCreate;
use super::{ApiError, Backoff, ErrorCode, HetznerApi, ListQuery, Location, ServerType};
use serde_json::{Value, json};
use std::time::Duration;
use wiremock::matchers::{body_partial_json, header, method, path, query_param};
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

const TOKEN: &str = "the-token";

fn api(server: &MockServer) -> HetznerApi {
    let fast = Backoff::new(3, Duration::from_millis(1), Duration::from_millis(2));
    HetznerApi::with_base_url(TOKEN, &server.uri())
        .with_retry(fast)
        .with_wait(fast)
}

fn ssh_key(id: u64, name: &str) -> Value {
    json!({
        "id": id,
        "name": name,
        "fingerprint": "99:fc:9b:f4:04:69:2c:9d:30:d5:2c:d9:1e:ca:b2:76",
        "public_key": "ssh-ed25519 AAAA",
        "labels": { "app": "sak" },
        "created": "2026-01-01T00:00:00+00:00"
    })
}

fn page(key: &str, items: Vec<Value>, page: u32, next_page: Option<u32>) -> Value {
    json!({
        key: items,
        "meta": { "pagination": { "page": page, "per_page": 50, "next_page": next_page, "total_entries": 3 } }
    })
}

fn action(id: u64, status: &str) -> Value {
    json!({
        "action": {
            "id": id, "command": "create_server", "status": status, "progress": if status == "running" { 50 } else { 100 },
            "started": "2026-01-01T00:00:00+00:00", "finished": null, "error": null,
            "resources": [{ "id": 1, "type": "server" }]
        }
    })
}

#[tokio::test]
async fn get_by_name_lists_with_the_name_filter_and_the_token() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/ssh_keys"))
        .and(query_param("name", "admin"))
        .and(query_param("page", "1"))
        .and(header("authorization", format!("Bearer {TOKEN}")))
        .respond_with(ResponseTemplate::new(200).set_body_json(page(
            "ssh_keys",
            vec![ssh_key(7, "admin")],
            1,
            None,
        )))
        .expect(1)
        .mount(&server)
        .await;

    let key = api(&server)
        .ssh_keys()
        .get_by_name("admin")
        .await
        .unwrap()
        .unwrap();

    assert_eq!(key.id, 7);
    assert_eq!(key.labels["app"], "sak");
}

#[tokio::test]
async fn an_empty_list_is_none() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/ssh_keys"))
        .respond_with(ResponseTemplate::new(200).set_body_json(page("ssh_keys", vec![], 1, None)))
        .mount(&server)
        .await;

    assert!(
        api(&server)
            .ssh_keys()
            .get_by_name("none")
            .await
            .unwrap()
            .is_none()
    );
}

#[tokio::test]
async fn list_follows_the_pages() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/ssh_keys"))
        .and(query_param("page", "1"))
        .respond_with(ResponseTemplate::new(200).set_body_json(page(
            "ssh_keys",
            vec![ssh_key(1, "a"), ssh_key(2, "b")],
            1,
            Some(2),
        )))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/ssh_keys"))
        .and(query_param("page", "2"))
        .respond_with(ResponseTemplate::new(200).set_body_json(page(
            "ssh_keys",
            vec![ssh_key(3, "c")],
            2,
            None,
        )))
        .mount(&server)
        .await;

    let keys = api(&server)
        .ssh_keys()
        .list(&ListQuery::new())
        .await
        .unwrap();

    assert_eq!(
        keys.iter().map(|key| key.id).collect::<Vec<_>>(),
        vec![1, 2, 3]
    );
}

#[tokio::test]
async fn get_by_id_is_none_for_a_404() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/ssh_keys/9"))
        .respond_with(
            ResponseTemplate::new(404)
                .set_body_json(json!({ "error": { "code": "not_found", "message": "gone" } })),
        )
        .mount(&server)
        .await;

    assert!(api(&server).ssh_keys().get(9).await.unwrap().is_none());
}

#[tokio::test]
async fn create_posts_the_body_and_reads_the_wrapped_answer() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/ssh_keys"))
        .and(body_partial_json(json!({ "name": "admin", "public_key": "ssh-ed25519 AAAA", "labels": { "app": "sak" } })))
        .respond_with(ResponseTemplate::new(201).set_body_json(json!({ "ssh_key": ssh_key(7, "admin") })))
        .expect(1)
        .mount(&server)
        .await;

    let request = SshKeyCreate {
        name: "admin".to_string(),
        public_key: "ssh-ed25519 AAAA".to_string(),
        labels: Some([("app".to_string(), "sak".to_string())].into()),
    };
    let key = api(&server).ssh_keys().create(&request).await.unwrap();

    assert_eq!(key.id, 7);
}

#[tokio::test]
async fn delete_is_true_when_deleted_and_false_for_a_404() {
    let server = MockServer::start().await;
    Mock::given(method("DELETE"))
        .and(path("/ssh_keys/7"))
        .respond_with(ResponseTemplate::new(204))
        .mount(&server)
        .await;
    Mock::given(method("DELETE"))
        .and(path("/ssh_keys/8"))
        .respond_with(
            ResponseTemplate::new(404)
                .set_body_json(json!({ "error": { "code": "not_found", "message": "gone" } })),
        )
        .mount(&server)
        .await;

    assert!(api(&server).ssh_keys().delete(7).await.unwrap());
    assert!(!api(&server).ssh_keys().delete(8).await.unwrap());
}

#[tokio::test]
async fn an_api_error_carries_its_code_and_message() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/ssh_keys"))
        .respond_with(ResponseTemplate::new(409).set_body_json(json!({
            "error": { "code": "uniqueness_error", "message": "SSH key with the same fingerprint already exists" }
        })))
        .mount(&server)
        .await;

    let request = SshKeyCreate {
        name: "admin".to_string(),
        public_key: "ssh-ed25519 AAAA".to_string(),
        labels: None,
    };
    let error = api(&server).ssh_keys().create(&request).await.unwrap_err();

    assert_eq!(error.code(), Some(ErrorCode::UniquenessError));
    assert_eq!(
        error.to_string(),
        "ssh_keys: SSH key with the same fingerprint already exists (UniquenessError)"
    );
}

#[tokio::test]
async fn an_unknown_error_code_and_a_non_json_error_are_errors_too() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/ssh_keys/1"))
        .respond_with(
            ResponseTemplate::new(422)
                .set_body_json(json!({ "error": { "code": "brand_new", "message": "no" } })),
        )
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/ssh_keys/2"))
        .respond_with(ResponseTemplate::new(502).set_body_string("bad gateway"))
        .mount(&server)
        .await;

    assert_eq!(
        api(&server).ssh_keys().get(1).await.unwrap_err().code(),
        Some(ErrorCode::Unknown)
    );
    let error = api(&server).ssh_keys().get(2).await.unwrap_err();
    assert!(
        matches!(error, ApiError::Unexpected { status: 502, .. }),
        "{error}"
    );
}

#[tokio::test]
async fn a_rate_limited_request_is_sent_again() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/ssh_keys/7"))
        .respond_with(ResponseTemplate::new(429).set_body_json(
            json!({ "error": { "code": "rate_limit_exceeded", "message": "slow down" } }),
        ))
        .up_to_n_times(2)
        .expect(2)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/ssh_keys/7"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "ssh_key": ssh_key(7, "admin") })),
        )
        .expect(1)
        .mount(&server)
        .await;

    let key = api(&server).ssh_keys().get(7).await.unwrap().unwrap();

    assert_eq!(key.name, "admin");
}

#[tokio::test]
async fn a_request_rate_limited_for_good_is_an_error() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/ssh_keys/7"))
        .respond_with(ResponseTemplate::new(429).set_body_json(
            json!({ "error": { "code": "rate_limit_exceeded", "message": "slow down" } }),
        ))
        .expect(3)
        .mount(&server)
        .await;

    let error = api(&server).ssh_keys().get(7).await.unwrap_err();

    assert_eq!(error.code(), Some(ErrorCode::RateLimitExceeded));
}

#[tokio::test]
async fn wait_for_action_polls_until_success() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/servers/actions/5"))
        .respond_with(ResponseTemplate::new(200).set_body_json(action(5, "running")))
        .up_to_n_times(2)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/servers/actions/5"))
        .respond_with(ResponseTemplate::new(200).set_body_json(action(5, "success")))
        .mount(&server)
        .await;

    let running = serde_json::from_value::<super::model::ActionResponse>(action(5, "running"))
        .unwrap()
        .action;
    let done = api(&server)
        .servers()
        .wait_for_action(&running)
        .await
        .unwrap();

    assert_eq!(done.status, super::ActionStatus::Success);
    assert_eq!(done.progress, 100);
}

#[tokio::test]
async fn a_failed_action_is_an_error_and_a_running_one_times_out() {
    let server = MockServer::start().await;
    let mut failed = action(6, "error");
    failed["action"]["error"] = json!({ "code": "server_error", "message": "no capacity" });
    Mock::given(method("GET"))
        .and(path("/servers/actions/6"))
        .respond_with(ResponseTemplate::new(200).set_body_json(failed))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/servers/actions/7"))
        .respond_with(ResponseTemplate::new(200).set_body_json(action(7, "running")))
        .expect(3)
        .mount(&server)
        .await;

    let error = api(&server).api_wait("servers", 6).await.unwrap_err();
    assert_eq!(
        error.to_string(),
        "action 'create_server' (6) failed: no capacity (server_error)"
    );

    let error = api(&server).api_wait("servers", 7).await.unwrap_err();
    assert!(
        matches!(error, ApiError::ActionTimeout { id: 7, .. }),
        "{error}"
    );
}

impl HetznerApi {
    async fn api_wait(&self, resource: &str, id: u64) -> super::Result<super::Action> {
        self.wait_for_action(resource, id).await
    }
}

#[tokio::test]
async fn a_server_is_created_with_its_primary_ips_and_user_data() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/servers"))
        .and(body_partial_json(json!({
            "name": "backend", "server_type": "cx23", "image": "debian-13", "location": "nbg1",
            "ssh_keys": [7], "volumes": [3], "user_data": "#cloud-config\n",
            "public_net": { "enable_ipv4": true, "enable_ipv6": true, "ipv4": 11, "ipv6": 12 }
        })))
        .respond_with(ResponseTemplate::new(201).set_body_json(json!({
            "server": {
                "id": 1, "name": "backend", "status": "initializing",
                "server_type": { "id": 1, "name": "cx23" }, "image": { "id": 2, "name": "debian-13" },
                "location": { "id": 3, "name": "nbg1" },
                "protection": { "delete": false, "rebuild": false }, "volumes": [3],
                "public_net": { "ipv4": { "id": 11, "ip": "1.2.3.4" }, "ipv6": { "id": 12, "ip": "2001:db8::/64" } },
                "private_net": [], "labels": {}
            },
            "action": action(5, "running")["action"]
        })))
        .expect(1)
        .mount(&server)
        .await;

    let request = ServerCreate {
        name: "backend".to_string(),
        server_type: ServerType::cx23,
        image: "debian-13".to_string(),
        location: Some(Location::nbg1),
        ssh_keys: vec![7],
        volumes: vec![3],
        firewalls: vec![],
        user_data: Some("#cloud-config\n".to_string()),
        public_net: Some(PublicNetCreate {
            enable_ipv4: true,
            enable_ipv6: true,
            ipv4: Some(11),
            ipv6: Some(12),
        }),
        labels: None,
        start_after_create: None,
    };
    let created = api(&server).servers().create(&request).await.unwrap();

    assert_eq!(created.server.server_type.name, ServerType::cx23);
    assert_eq!(created.server.image_name(), Some("debian-13"));
    assert_eq!(created.server.public_ipv4(), Some("1.2.3.4"));
    assert_eq!(created.server.public_net.ipv4.unwrap().id, Some(11));
    assert_eq!(created.action.id, 5);
}

#[tokio::test]
async fn a_primary_ip_is_created_unassigned_in_a_location() {
    let server = MockServer::start().await;
    let no_assignee = |request: &Request| {
        let body: Value = serde_json::from_slice(&request.body).unwrap_or_default();
        body.get("assignee_id").is_none() && body.get("assignee_type").is_none()
    };
    Mock::given(method("POST"))
        .and(path("/primary_ips"))
        .and(body_partial_json(json!({ "name": "backend", "type": "ipv4", "location": "nbg1", "auto_delete": false })))
        .and(no_assignee)
        .respond_with(ResponseTemplate::new(201).set_body_json(json!({
            "primary_ip": {
                "id": 11, "name": "backend", "ip": "1.2.3.4", "type": "ipv4", "assignee_id": null, "assignee_type": "unassigned",
                "auto_delete": false, "protection": { "delete": false }, "location": { "id": 3, "name": "nbg1" }, "labels": {}
            },
            "action": null
        })))
        .expect(1)
        .mount(&server)
        .await;

    let request = PrimaryIpCreate {
        name: "backend".to_string(),
        ip_type: PrimaryIpType::Ipv4,
        assignee_type: None,
        assignee_id: None,
        location: Some(Location::nbg1),
        auto_delete: false,
        labels: None,
    };
    let created = api(&server).primary_ips().create(&request).await.unwrap();

    assert_eq!(created.primary_ip.ip, "1.2.3.4");
    assert_eq!(created.primary_ip.location.name, Location::nbg1);
    assert_eq!(
        created.primary_ip.assignee_type,
        Some(AssigneeType::Unassigned)
    );
    assert!(created.primary_ip.assignee_id.is_none());
    assert!(created.action.is_none());
}
