//! the password of a deployment proven against its resources, and the endpoint of its backend

use super::*;

/// checks the password before anything changes: every hash label on the resources must match;
/// only when none has one, against the public client key (`check_password`)
pub(super) fn verify_password(
    deployment: &DiscoveredDeployment,
    name: &str,
    password: &str,
) -> Result<(), String> {
    let (hashes, key) = password_proofs(deployment);
    check_password(&hashes, key, name, password)
}

/// where a deployment answers its users. A request goes to `address` directly: a resolver of this
/// machine can still know the address of an earlier server of the name
pub struct Endpoint {
    pub url: String,
    pub(super) key: crate::core::keys::ApiKey,
    pub trust: crate::core::config::Trust,
    pub address: Option<std::net::IpAddr>,
}

/// the endpoint of `deployment`, password checked. The trust follows the ca this machine kept
/// (`local`); without, also the staging roots, so a deployment of either answers. That is safe:
/// the request is signed and goes to the server of the deployment
pub(super) fn endpoint(
    deployment: &DiscoveredDeployment,
    password: &str,
) -> Result<Endpoint, String> {
    let (url, key) = config_endpoint(deployment, &deployment.name, password)?;
    Ok(Endpoint {
        url,
        key,
        trust: local_settings(&deployment.name, password)
            .and_then(|settings| settings.acme_ca)
            .map(acme_trust)
            .unwrap_or(crate::core::config::Trust::LetsEncryptStaging),
        address: deployment
            .server
            .as_ref()
            .and_then(|server| server.public_ipv4.as_deref())
            .and_then(|address| address.parse().ok()),
    })
}

/// `GET /v1/deployment`
pub(super) async fn deployment_settings_of(
    deployment: &DiscoveredDeployment,
    password: &str,
) -> Result<crate::core::config::DeploymentSettings, String> {
    let endpoint = endpoint(deployment, password)?;
    crate::core::config::fetch_deployment(
        &endpoint.url,
        &endpoint.key,
        endpoint.trust,
        endpoint.address,
    )
    .await
}

/// the api of the base domain on the labels and the api key of the password. The password is
/// checked first, so nothing is signed with the key of a wrong password
pub(super) fn config_endpoint(
    deployment: &DiscoveredDeployment,
    name: &str,
    password: &str,
) -> Result<(String, crate::core::keys::ApiKey), String> {
    verify_password(deployment, name, password)?;
    let root_domain = deployment.root_domain().ok_or_else(|| {
        t!(
            "common.config.no_root_domain",
            name = name,
            hint = crate::core::i18n::hint(crate::core::i18n::Hint::RootDomain)
        )
        .to_string()
    })?;
    let base = sak_cloud::hetzner::deployment::base_domain(
        name,
        deployment.dns_mode().unwrap_or_default(),
        &root_domain,
    );
    let url = crate::core::config::api_url(&base);
    let key = crate::core::keys::api_key(name, password)?;
    Ok((url, key))
}

/// the password hashes on the resources and the public key of the ssh key
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

/// with no hash and no key the password cannot be checked and is refused
pub(super) fn check_password(
    hashes: &[String],
    key: Option<&str>,
    name: &str,
    password: &str,
) -> Result<(), String> {
    let wrong = || t!("common.deployments.wrong_password", name = name).to_string();
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
    Err(t!("common.deployments.unverifiable", name = name).to_string())
}
