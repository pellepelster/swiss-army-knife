//! the client against a mock of the api: paths, bodies and the token of the requests,
//! pagination, the rate limit retry, action polling and the errors

use super::firewalls::{FirewallRule, RuleDirection, RuleProtocol};
use super::primary_ips::{AssigneeType, PrimaryIpCreate, PrimaryIpType};
use super::servers::{PublicNetCreate, ServerCreate};
use super::ssh_keys::SshKeyCreate;
use super::volumes::{VolumeCreate, VolumeFormat, VolumeUpdate};
use super::{ApiError, Backoff, ErrorCode, HetznerApi, ListQuery, Location, ServerType};
use serde_json::{Value, json};
use std::time::Duration;
use wiremock::matchers::{body_json, body_partial_json, header, method, path, query_param};
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

fn error(status: u16, code: &str) -> ResponseTemplate {
    ResponseTemplate::new(status)
        .set_body_json(json!({ "error": { "code": code, "message": "busy" } }))
}

fn create_request() -> SshKeyCreate {
    SshKeyCreate {
        name: "admin".to_string(),
        public_key: "ssh-ed25519 AAAA".to_string(),
        labels: None,
    }
}

#[tokio::test]
async fn a_read_is_sent_again_after_a_server_error() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/ssh_keys/7"))
        .respond_with(ResponseTemplate::new(503).set_body_string("unavailable"))
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

    assert_eq!(
        api(&server).ssh_keys().get(7).await.unwrap().unwrap().name,
        "admin"
    );
}

#[tokio::test]
async fn a_create_is_not_sent_again_after_a_server_error() {
    // hetzner may have created the key before it failed, a second create would make another
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/ssh_keys"))
        .respond_with(ResponseTemplate::new(503).set_body_string("unavailable"))
        .expect(1)
        .mount(&server)
        .await;

    let error = api(&server)
        .ssh_keys()
        .create(&create_request())
        .await
        .unwrap_err();
    assert!(
        matches!(error, ApiError::Unexpected { status: 503, .. }),
        "{error}"
    );
}

#[tokio::test]
async fn a_request_on_a_locked_or_conflicting_resource_is_sent_again() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/ssh_keys"))
        .respond_with(error(423, "locked"))
        .up_to_n_times(1)
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/ssh_keys"))
        .respond_with(error(409, "conflict"))
        .up_to_n_times(1)
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/ssh_keys"))
        .respond_with(
            ResponseTemplate::new(201).set_body_json(json!({ "ssh_key": ssh_key(7, "admin") })),
        )
        .expect(1)
        .mount(&server)
        .await;

    let key = api(&server)
        .ssh_keys()
        .create(&create_request())
        .await
        .unwrap();
    assert_eq!(key.name, "admin");
}

#[tokio::test]
async fn a_conflict_of_another_kind_is_an_error_at_once() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/ssh_keys"))
        .respond_with(error(409, "uniqueness_error"))
        .expect(1)
        .mount(&server)
        .await;

    let error = api(&server)
        .ssh_keys()
        .create(&create_request())
        .await
        .unwrap_err();
    // the answer read for its code is the error, its message too
    assert_eq!(error.code(), Some(ErrorCode::UniquenessError));
    assert!(error.to_string().contains("busy"), "{error}");
}

#[tokio::test]
async fn a_read_is_sent_again_after_a_connection_error() {
    // a listener that closes every connection at once: every attempt fails, the last one is the
    // error
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let connections = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let counted = connections.clone();
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            counted.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            drop(stream);
        }
    });
    let api = HetznerApi::with_base_url(TOKEN, &format!("http://{address}")).with_retry(
        Backoff::new(3, Duration::from_millis(1), Duration::from_millis(2)),
    );

    let error = api.ssh_keys().get(7).await.unwrap_err();

    assert!(matches!(error, ApiError::Request { .. }), "{error}");
    assert_eq!(connections.load(std::sync::atomic::Ordering::SeqCst), 3);
}

#[tokio::test]
async fn a_backoff_of_zero_attempts_tries_once() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/ssh_keys/7"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "ssh_key": ssh_key(7, "admin") })),
        )
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/servers/actions/8"))
        .respond_with(ResponseTemplate::new(200).set_body_json(action(8, "running")))
        .expect(1)
        .mount(&server)
        .await;
    let none = Backoff::new(0, Duration::ZERO, Duration::ZERO);
    let api = HetznerApi::with_base_url(TOKEN, &server.uri())
        .with_retry(none)
        .with_wait(none);

    assert!(api.ssh_keys().get(7).await.unwrap().is_some());
    let error = api.wait_for_action("servers", 8).await.unwrap_err();
    assert!(
        matches!(error, ApiError::ActionTimeout { id: 8, .. }),
        "{error}"
    );
}

#[test]
fn a_rate_limit_waits_until_its_reset_within_bounds() {
    let response = |reset: Option<u64>| {
        let mut response = http::Response::new(String::new());
        *response.status_mut() = http::StatusCode::TOO_MANY_REQUESTS;
        if let Some(reset) = reset {
            response
                .headers_mut()
                .insert("ratelimit-reset", reset.to_string().parse().unwrap());
        }
        reqwest::Response::from(response)
    };
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs();
    let backoff = Duration::from_secs(1);
    // in 5 seconds; about now, at least the backoff; far away, at most a minute; none, the backoff
    let in_five = super::rate_limit_delay(&response(Some(now + 5)), backoff);
    assert!(
        in_five > Duration::from_secs(3) && in_five <= Duration::from_secs(5),
        "{in_five:?}"
    );
    assert_eq!(
        super::rate_limit_delay(&response(Some(now)), backoff),
        backoff
    );
    assert_eq!(
        super::rate_limit_delay(&response(Some(now + 3600)), backoff),
        Duration::from_secs(60)
    );
    assert_eq!(super::rate_limit_delay(&response(None), backoff), backoff);
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

    let error = api(&server)
        .wait_for_action("servers", 6)
        .await
        .unwrap_err();
    assert_eq!(
        error.to_string(),
        "action 'create_server' (6) failed: no capacity (server_error)"
    );

    let error = api(&server)
        .wait_for_action("servers", 7)
        .await
        .unwrap_err();
    assert!(
        matches!(error, ApiError::ActionTimeout { id: 7, .. }),
        "{error}"
    );
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

fn volume(id: u64, name: &str) -> Value {
    json!({
        "id": id, "name": name, "format": "ext4", "linux_device": format!("/dev/disk/by-id/scsi-0HC_Volume_{id}"),
        "size": 10, "protection": { "delete": false }, "server": null, "status": "available",
        "location": { "id": 3, "name": "nbg1" }, "labels": {}
    })
}

#[tokio::test]
async fn a_volume_is_read_beside_the_actions_of_its_answer_and_updated_with_a_put() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/volumes"))
        .and(body_partial_json(json!({ "name": "data", "size": 10, "location": "nbg1", "format": "ext4", "automount": false })))
        .respond_with(ResponseTemplate::new(201).set_body_json(json!({
            "action": action(5, "running")["action"],
            "volume": volume(3, "data"),
            "next_actions": []
        })))
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("PUT"))
        .and(path("/volumes/3"))
        .and(body_partial_json(json!({ "name": "backup" })))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "volume": volume(3, "backup") })),
        )
        .expect(1)
        .mount(&server)
        .await;

    let request = VolumeCreate {
        name: "data".to_string(),
        size: 10,
        location: Location::nbg1,
        format: VolumeFormat::Ext4,
        automount: false,
        labels: None,
    };
    let created = api(&server).volumes().create(&request).await.unwrap();
    assert_eq!(created.id, 3);
    assert_eq!(created.format, Some(VolumeFormat::Ext4));

    let update = VolumeUpdate {
        name: Some("backup".to_string()),
        labels: None,
    };
    let updated = api(&server).volumes().update(3, &update).await.unwrap();
    assert_eq!(updated.name, "backup");
}

#[tokio::test]
async fn an_answer_without_its_key_is_an_error() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/volumes/3"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "server": volume(3, "data") })),
        )
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/volumes"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "volumes": [] })))
        .mount(&server)
        .await;

    let error = api(&server).volumes().get(3).await.unwrap_err();
    assert!(matches!(error, ApiError::Request { .. }), "{error}");

    let error = api(&server)
        .volumes()
        .list(&ListQuery::new())
        .await
        .unwrap_err();
    assert!(matches!(error, ApiError::Request { .. }), "{error}");
}

#[tokio::test]
async fn a_server_delete_answers_its_action_and_none_for_a_404() {
    let server = MockServer::start().await;
    Mock::given(method("DELETE"))
        .and(path("/servers/1"))
        .respond_with(ResponseTemplate::new(200).set_body_json(action(5, "running")))
        .mount(&server)
        .await;
    Mock::given(method("DELETE"))
        .and(path("/servers/2"))
        .respond_with(error(404, "not_found"))
        .mount(&server)
        .await;

    let action = api(&server).servers().delete(1).await.unwrap().unwrap();
    assert_eq!(action.id, 5);
    assert!(api(&server).servers().delete(2).await.unwrap().is_none());
}

#[tokio::test]
async fn the_protection_of_a_server_covers_the_rebuild_and_that_of_a_volume_does_not() {
    let server = MockServer::start().await;
    let no_rebuild = |request: &Request| {
        let body: Value = serde_json::from_slice(&request.body).unwrap_or_default();
        body.get("rebuild").is_none()
    };
    Mock::given(method("POST"))
        .and(path("/servers/1/actions/change_protection"))
        .and(body_partial_json(
            json!({ "delete": true, "rebuild": true }),
        ))
        .respond_with(ResponseTemplate::new(201).set_body_json(action(5, "running")))
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/volumes/3/actions/change_protection"))
        .and(body_partial_json(json!({ "delete": false })))
        .and(no_rebuild)
        .respond_with(ResponseTemplate::new(201).set_body_json(action(6, "running")))
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/primary_ips/11/actions/change_protection"))
        .and(body_partial_json(json!({ "delete": true })))
        .and(no_rebuild)
        .respond_with(ResponseTemplate::new(201).set_body_json(action(7, "running")))
        .expect(1)
        .mount(&server)
        .await;

    let api = api(&server);
    assert_eq!(
        api.servers().change_protection(1, true).await.unwrap().id,
        5
    );
    assert_eq!(
        api.volumes().change_protection(3, false).await.unwrap().id,
        6
    );
    assert_eq!(
        api.primary_ips()
            .change_protection(11, true)
            .await
            .unwrap()
            .id,
        7
    );
}

#[tokio::test]
async fn the_actions_of_a_kind_post_to_their_command_with_or_without_a_body() {
    let server = MockServer::start().await;
    let no_body = |request: &Request| request.body.is_empty();
    for (action_path, id) in [
        ("/servers/1/actions/poweron", 1),
        ("/servers/1/actions/shutdown", 2),
        ("/volumes/3/actions/detach", 3),
        ("/primary_ips/11/actions/unassign", 4),
    ] {
        Mock::given(method("POST"))
            .and(path(action_path))
            .and(no_body)
            .respond_with(ResponseTemplate::new(201).set_body_json(action(id, "running")))
            .expect(1)
            .mount(&server)
            .await;
    }
    Mock::given(method("POST"))
        .and(path("/volumes/3/actions/attach"))
        .and(body_partial_json(
            json!({ "server": 1, "automount": false }),
        ))
        .respond_with(ResponseTemplate::new(201).set_body_json(action(5, "running")))
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/primary_ips/11/actions/assign"))
        .and(body_partial_json(
            json!({ "assignee_id": 1, "assignee_type": "server" }),
        ))
        .respond_with(ResponseTemplate::new(201).set_body_json(action(6, "running")))
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/servers/1/actions/change_type"))
        .and(body_partial_json(
            json!({ "server_type": "cx33", "upgrade_disk": false }),
        ))
        .respond_with(ResponseTemplate::new(201).set_body_json(action(7, "running")))
        .expect(1)
        .mount(&server)
        .await;

    let api = api(&server);
    assert_eq!(api.servers().power_on(1).await.unwrap().id, 1);
    assert_eq!(api.servers().shutdown(1).await.unwrap().id, 2);
    assert_eq!(api.volumes().detach(3).await.unwrap().id, 3);
    assert_eq!(api.primary_ips().unassign(11).await.unwrap().id, 4);
    assert_eq!(api.volumes().attach(3, 1, false).await.unwrap().id, 5);
    assert_eq!(api.primary_ips().assign(11, 1).await.unwrap().id, 6);
    assert_eq!(
        api.servers()
            .change_type(1, ServerType::cx33, false)
            .await
            .unwrap()
            .id,
        7
    );
}

#[tokio::test]
async fn the_rules_of_a_firewall_are_set_with_one_action_per_server_and_waited_for() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/firewalls/4/actions/set_rules"))
        .and(body_partial_json(
            json!({ "rules": [{ "direction": "in", "protocol": "tcp", "port": "22" }] }),
        ))
        .respond_with(ResponseTemplate::new(201).set_body_json(json!({
            "actions": [action(5, "running")["action"], action(6, "running")["action"]]
        })))
        .expect(1)
        .mount(&server)
        .await;
    for id in [5, 6] {
        Mock::given(method("GET"))
            .and(path(format!("/firewalls/actions/{id}")))
            .respond_with(ResponseTemplate::new(200).set_body_json(action(id, "success")))
            .expect(1)
            .mount(&server)
            .await;
    }

    let rule = FirewallRule {
        direction: RuleDirection::In,
        protocol: RuleProtocol::Tcp,
        port: Some("22".to_string()),
        source_ips: vec!["0.0.0.0/0".to_string()],
        destination_ips: vec![],
        description: None,
    };
    let api = api(&server);
    let firewalls = api.firewalls();
    let actions = firewalls.set_rules(4, &[rule]).await.unwrap();
    assert_eq!(
        actions.iter().map(|action| action.id).collect::<Vec<_>>(),
        vec![5, 6]
    );
    firewalls.wait_for_actions(&actions).await.unwrap();
}

#[tokio::test]
async fn set_labels_changes_and_removes_labels_and_keeps_the_others() {
    let labeled = |labels: Value| {
        json!({ "server": {
            "id": 1, "name": "sak-alpha", "status": "running", "server_type": { "id": 1, "name": "cx23" },
            "image": { "id": 2, "name": "debian-13" }, "location": { "id": 3, "name": "hel1" },
            "protection": { "delete": false, "rebuild": false }, "volumes": [],
            "public_net": { "ipv4": null, "ipv6": null }, "private_net": [], "labels": labels
        } })
    };
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/servers/1"))
        .respond_with(ResponseTemplate::new(200).set_body_json(labeled(
            json!({ "keep": "1", "change": "old", "remove": "x" }),
        )))
        .mount(&server)
        .await;
    let updated = json!({ "keep": "1", "change": "new", "add": "2" });
    Mock::given(method("PUT"))
        .and(path("/servers/1"))
        .and(body_json(json!({ "labels": updated })))
        .respond_with(ResponseTemplate::new(200).set_body_json(labeled(updated.clone())))
        .expect(1)
        .mount(&server)
        .await;

    let changes = [
        ("change", Some("new")),
        ("add", Some("2")),
        ("remove", None),
    ];
    let set = api(&server)
        .servers()
        .set_labels(1, &changes)
        .await
        .unwrap();

    assert_eq!(set.unwrap().labels["change"], "new");
    // a server that is gone
    assert!(
        api(&server)
            .servers()
            .set_labels(2, &changes)
            .await
            .unwrap()
            .is_none()
    );
}
