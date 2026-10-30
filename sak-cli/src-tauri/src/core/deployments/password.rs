//! the password of a deployment proven against its resources, and the endpoint of its backend

use super::*;

/// checks the password of an existing deployment before anything changes: against the hash labels
/// on its resources, where every hash must match. Only when no resource has one (created before
/// the resources carried it), against the public client key; with neither, it is refused
/// (`check_password`)
pub(super) fn verify_password(
    deployment: &DiscoveredDeployment,
    name: &str,
    password: &str,
) -> Result<(), String> {
    let (hashes, key) = password_proofs(deployment);
    check_password(&hashes, key, name, password)
}

/// where a deployment answers its users: its api, the key a request to it is signed with, the trust
/// of its certificates and the address of its server, which a request asks directly because a
/// resolver of this machine can still know the address of an earlier server of the name
pub struct Endpoint {
    pub url: String,
    pub(super) key: crate::core::keys::ApiKey,
    pub trust: crate::core::config::Trust,
    pub address: Option<std::net::IpAddr>,
}

/// the endpoint of `deployment`, the password checked against it first
pub(super) fn endpoint(
    deployment: &DiscoveredDeployment,
    password: &str,
) -> Result<Endpoint, String> {
    let (url, key) = config_endpoint(deployment, &deployment.name, password)?;
    Ok(Endpoint {
        url,
        key,
        trust: acme_trust(deployment.acme_ca().unwrap_or_default()),
        address: deployment
            .server
            .as_ref()
            .and_then(|server| server.public_ipv4.as_deref())
            .and_then(|address| address.parse().ok()),
    })
}

/// the running configuration of `deployment`, from its backend
pub(super) async fn running_config_of(
    deployment: &DiscoveredDeployment,
    password: &str,
) -> Result<crate::core::config::RunningConfig, String> {
    let endpoint = endpoint(deployment, password)?;
    crate::core::config::fetch(
        &endpoint.url,
        &endpoint.key,
        endpoint.trust,
        endpoint.address,
    )
    .await
}

/// where `config show` asks and with which key: the api of the base domain on the labels of the
/// deployment, and the api key of the password. sakctl checks the password first, so it signs
/// nothing with the key of a wrong password
pub(super) fn config_endpoint(
    deployment: &DiscoveredDeployment,
    name: &str,
    password: &str,
) -> Result<(String, crate::core::keys::ApiKey), String> {
    verify_password(deployment, name, password)?;
    let root_domain = deployment
        .root_domain()
        .ok_or_else(|| t!("shared.config.no_root_domain", name = name).to_string())?;
    let base = sak_cloud::hetzner::deployment::base_domain(
        name,
        deployment.dns_mode().unwrap_or_default(),
        &root_domain,
    );
    let url = crate::core::config::api_url(&base);
    let key = crate::core::keys::api_key(name, password)?;
    Ok((url, key))
}

/// what a password can be checked against: the password hashes on the resources and the public
/// key of the ssh key. Both are empty when only resources without labels are left
pub(super) fn password_proofs(deployment: &DiscoveredDeployment) -> (Vec<String>, Option<&str>) {
    let hashes: Vec<String> = deployment
        .labels()
        .into_iter()
        .filter_map(|raw| {
            HetznerLabels::from_raw(raw.clone())
                .labels()
                .get(PASSWORD_HASH_LABEL)
                .cloned()
        })
        .collect();
    let key = deployment
        .ssh_key
        .as_ref()
        .map(|key| key.public_key.as_str());
    (hashes, key)
}

/// `hashes` are the password hashes on the resources, `key` is the public key of the ssh key.
/// When there is no hash and no key, sakctl cannot check the password and refuses it, so it does
/// not take every password
pub(super) fn check_password(
    hashes: &[String],
    key: Option<&str>,
    name: &str,
    password: &str,
) -> Result<(), String> {
    let wrong = || t!("shared.deployments.wrong_password", name = name).to_string();
    if !hashes.is_empty() {
        let hash = crate::core::keys::password_hash(name, password)?;
        return match hashes.iter().all(|found| *found == hash) {
            true => Ok(()),
            false => Err(wrong()),
        };
    }
    if let Some(key) = key {
        let derived = crate::core::keys::derive_public_key(name, password)?;
        // the blob alone, hetzner may keep the key without its comment
        if key.split_whitespace().nth(1) != derived.split_whitespace().nth(1) {
            return Err(wrong());
        }
        return Ok(());
    }
    Err(t!("shared.deployments.unverifiable", name = name).to_string())
}
