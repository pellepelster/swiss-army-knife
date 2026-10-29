//! a backend at hetzner, declared in code: an ssh key, a volume, a primary ip, a firewall
//! and a server created from them with cloud-init. Prints what apply would do; applies
//! with `--apply` (`--recreate` allows recreates), destroys with `--destroy`.
//!
//!     HCLOUD_TOKEN=... cargo run --example backend -- --apply

use sak_cloud::api::{Resource, diff};
use sak_cloud::hetzner::api::{Location, ServerType};
use sak_cloud::hetzner::firewall::{HetznerFirewall, inbound_icmp, inbound_tcp};
use sak_cloud::hetzner::primary_ip::HetznerPrimaryIp;
use sak_cloud::hetzner::server::HetznerServer;
use sak_cloud::hetzner::ssh_key::HetznerSshKey;
use sak_cloud::hetzner::volume::HetznerVolume;
use sak_cloud::providers::ProviderManager;
use sak_cloud::providers::hetzner::{HetznerProviderConfiguration, HetznerProviderManager};
use std::collections::BTreeMap;

const USER_DATA: &str = "#cloud-config
package_update: true
packages:
  - podman
";

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt().with_env_filter("info").init();
    let arguments: Vec<String> = std::env::args().skip(1).collect();
    let token = std::env::var("HCLOUD_TOKEN").map_err(|_| "HCLOUD_TOKEN is not set")?;
    let public_key = std::env::var("SSH_PUBLIC_KEY")
        .map_err(|_| "SSH_PUBLIC_KEY is not set, the key to log in with")?;

    let configuration = HetznerProviderConfiguration::new("default", &token)
        .with_defaults(Location::hel1, ServerType::cx23);
    let hetzner = HetznerProviderManager.validate(&configuration).await?;

    let labels: BTreeMap<String, String> = [("app".to_string(), "sak".to_string())].into();
    let key = HetznerSshKey::new("sak-admin", &public_key).with_labels(labels.clone());
    let volume =
        HetznerVolume::new("sak-data", hetzner.default_location, 16).with_labels(labels.clone());
    let ip =
        HetznerPrimaryIp::ipv4("sak-backend", hetzner.default_location).with_labels(labels.clone());
    let firewall = HetznerFirewall::new(
        "sak-backend",
        vec![inbound_tcp("22"), inbound_tcp("443"), inbound_icmp()],
    )
    .applied_to(labels.clone())
    .with_labels(labels.clone());
    let server = HetznerServer::new(
        "sak-backend",
        hetzner.default_location,
        hetzner.default_server_type,
        USER_DATA,
    )
    .with_ssh_keys(vec![key.as_lookup()])
    .with_volumes(vec![volume.as_lookup()])
    .with_ipv4(ip.as_lookup())
    .with_labels(labels.clone());

    let server_ref = server.resource_ref();
    // what a destroy may remove: only resources that carry these labels
    let mut registry = hetzner
        .registry()
        .owned_by(labels.clone())
        .with(key)?
        .with(volume)?
        .with(ip)?
        .with(firewall)?
        .with(server)?;

    if arguments.iter().any(|argument| argument == "--destroy") {
        for destroyed in registry.destroy_all().await? {
            println!("destroyed {destroyed}");
        }
        return Ok(());
    }

    let diffs = registry.diff_all().await?;
    print!("{}", diff::log_text(&diffs));

    if arguments.iter().any(|argument| argument == "--apply") {
        // --recreate lets the server be created anew, nothing else ever is
        if arguments.iter().any(|argument| argument == "--recreate") {
            registry = registry.recreatable(server_ref);
        }
        for result in registry.apply_all().await? {
            println!("{}: {:?}", result.resource, result.applied);
        }
    }
    Ok(())
}
