//! the projects and resources of the tests of the deployments, as the mock api answers them

use crate::hetzner::api::{HetznerApi, Location, ServerType};
use crate::hetzner::deployment::{DeploymentSpec, DnsMode};
use crate::hetzner::labels::HetznerLabels;
use crate::hetzner::test_support::mock_api_all;
use crate::hetzner::{DNS_MODE_LABEL, NAME_LABEL, SSH_KEYS_LABEL, USER_DATA_LABEL};
use serde_json::{Value, json};
use std::sync::Arc;

pub(super) const FINGERPRINT: &str = "99:fc:9b:f4:04:69:2c:9d:30:d5:2c:d9:1e:ca:b2:76";

pub(super) fn named(name: &str) -> Value {
    json!({ NAME_LABEL: name })
}

pub(super) fn key(id: u64, name: &str, labels: Value) -> Value {
    json!({ "id": id, "name": name, "fingerprint": FINGERPRINT, "public_key": "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE+u0dEVRZDzzp4E1teCqF49r8ig3YEk8eaPqNWfDcPb pelle@fry", "labels": labels })
}

pub(super) fn firewall(id: u64, name: &str, labels: Value) -> Value {
    json!({ "id": id, "name": name, "rules": [{ "direction": "in", "protocol": "icmp", "source_ips": ["0.0.0.0/0"] }], "applied_to": [], "labels": labels })
}

/// a server created with the key of the tests and the user data `#cloud-config\n`
pub(super) fn server(id: u64, name: &str, mut labels: Value) -> Value {
    let mut hetzner = HetznerLabels::new();
    hetzner.add_hashed(SSH_KEYS_LABEL, FINGERPRINT).unwrap();
    hetzner
        .add_hashed(USER_DATA_LABEL, "#cloud-config\n")
        .unwrap();
    for (key, value) in hetzner.raw() {
        labels[key] = json!(value);
    }
    json!({
        "id": id, "name": name, "status": "running", "server_type": { "id": 1, "name": "cx23" },
        "image": { "id": 2, "name": "debian-13" }, "location": { "id": 3, "name": "hel1" },
        "protection": { "delete": false, "rebuild": false }, "volumes": [],
        "public_net": { "ipv4": { "id": null, "ip": "1.2.3.4" }, "ipv6": null }, "private_net": [], "labels": labels
    })
}

pub(super) async fn project() -> (wiremock::MockServer, Arc<HetznerApi>) {
    mock_api_all(&[
        (
            "servers",
            vec![
                server(1, "sak-alpha", named("alpha")),
                server(2, "other", json!({})),
            ],
        ),
        (
            "ssh_keys",
            vec![
                key(7, "sak-alpha", named("alpha")),
                key(
                    8,
                    "sak-beta",
                    json!({ NAME_LABEL: "beta", DNS_MODE_LABEL: "subdomain" }),
                ),
            ],
        ),
        ("firewalls", vec![firewall(5, "sak-alpha", named("alpha"))]),
        ("volumes", vec![]),
        ("primary_ips", vec![]),
    ])
    .await
}

pub(super) fn volume(id: u64, name: &str, location: &str, labels: Value) -> Value {
    json!({
        "id": id, "name": name, "format": "ext4", "linux_device": format!("/dev/disk/by-id/scsi-0HC_Volume_{id}"),
        "size": 16, "protection": { "delete": true }, "server": null, "status": "available",
        "location": { "id": 2, "name": location }, "labels": labels
    })
}

pub(super) fn spec(dns_mode: DnsMode) -> DeploymentSpec {
    DeploymentSpec::new(
        "alpha",
        "ssh-ed25519 AAAA",
        Location::hel1,
        ServerType::cx23,
    )
    .unwrap()
    .with_dns_mode(dns_mode)
    .with_root_domain("pelle.io")
}

/// a project with a `web` server, ssh key, firewall, data volume and primary ips without
/// labels, protected where hetzner allows it: no deployment created them
pub(super) fn foreign_volume() -> Value {
    json!({
        "id": 3, "name": "sak-web-data", "format": "ext4", "linux_device": "/dev/disk/by-id/scsi-0HC_Volume_3", "size": 16,
        "protection": { "delete": true }, "server": null, "status": "available",
        "location": { "id": 2, "name": "hel1" }, "labels": {}
    })
}

pub(super) fn foreign_ip(id: u64, name: &str, ip_type: &str) -> Value {
    json!({
        "id": id, "name": name, "ip": "1.2.3.4", "type": ip_type, "assignee_id": null, "assignee_type": "unassigned",
        "auto_delete": false, "protection": { "delete": true }, "location": { "id": 3, "name": "hel1" }, "labels": {}
    })
}

pub(super) async fn foreign_web() -> (wiremock::MockServer, Arc<HetznerApi>) {
    let (volume, ip) = (foreign_volume(), foreign_ip);
    mock_api_all(&[
        ("servers", vec![server(1, "sak-web", json!({}))]),
        ("ssh_keys", vec![key(7, "sak-web", json!({}))]),
        ("firewalls", vec![firewall(5, "sak-web", json!({}))]),
        ("volumes", vec![volume]),
        (
            "primary_ips",
            vec![
                ip(11, "sak-web-ipv4", "ipv4"),
                ip(12, "sak-web-ipv6", "ipv6"),
            ],
        ),
    ])
    .await
}

/// the requests to the mock that change something: all requests except reads
pub(super) async fn writes(server: &wiremock::MockServer) -> Vec<String> {
    server
        .received_requests()
        .await
        .unwrap_or_default()
        .iter()
        .filter(|request| request.method != wiremock::http::Method::GET)
        .map(|request| format!("{} {}", request.method, request.url.path()))
        .collect()
}

pub(super) fn web() -> DeploymentSpec {
    DeploymentSpec::new("web", "ssh-ed25519 AAAA", Location::hel1, ServerType::cx23)
        .unwrap()
        .with_password_hash("any-password-hash")
}
