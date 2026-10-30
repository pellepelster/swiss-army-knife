//! the root domains a deployment can use, and the dns mode it is deployed in

use super::*;

/// a dns zone of the project as the page offers it as root domain: the dns modes the deployment
/// can use it in (none when another deployment manages it in domain mode), and why
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RootDomainResponse {
    pub(crate) name: String,
    pub(crate) dns_modes: Vec<DnsMode>,
    /// why a mode is not possible, none when both are
    pub(crate) note: Option<String>,
}

/// the root domain as the deployment `name` can use it
pub(super) fn root_domain_response(domain: &RootDomain, name: &str) -> RootDomainResponse {
    let note = DnsMode::ALL
        .into_iter()
        .find_map(|mode| domain.check(name, mode).err())
        .map(|conflict| conflict_text(&domain.name, &conflict));
    RootDomainResponse {
        name: domain.name.clone(),
        dns_modes: domain.dns_modes(name),
        note,
    }
}

/// the deployments that use a root domain, `beta, gamma`; one without a name as such
pub(super) fn by_text(by: &[Option<String>]) -> String {
    by.iter()
        .map(|name| match name {
            Some(name) => name.clone(),
            None => t!("shared.deployments.unnamed").to_string(),
        })
        .collect::<Vec<_>>()
        .join(", ")
}

pub(super) fn conflict_text(domain: &str, conflict: &RootDomainConflict) -> String {
    let (key, by) = match conflict {
        RootDomainConflict::DomainTaken { by } => ("shared.deployments.domain_taken", by),
        RootDomainConflict::MixedModes {
            used: DnsMode::Domain,
            by,
        } => ("shared.deployments.domain_mixed", by),
        RootDomainConflict::MixedModes {
            used: DnsMode::Subdomain,
            by,
        } => ("shared.deployments.subdomain_only", by),
    };
    t!(key, domain = domain, by = by_text(by)).to_string()
}

/// the exit code of the command line for a root domain that cannot be used
pub(super) fn conflict_exit_code(conflict: &RootDomainConflict) -> i32 {
    match conflict {
        RootDomainConflict::DomainTaken { .. } => EXIT_DOMAIN_TAKEN,
        RootDomainConflict::MixedModes { .. } => EXIT_MIXED_DNS_MODES,
    }
}

/// checks the root domain `chosen` for the deployment `name` in `dns_mode`: it is a zone of the
/// project and has no conflict with the deployments that use it
pub(super) fn check_root_domain(
    domains: &[RootDomain],
    chosen: Option<&str>,
    name: &str,
    dns_mode: DnsMode,
) -> Result<String, SakctlError> {
    if domains.is_empty() {
        return Err(t!("shared.deployments.no_zones").to_string().into());
    }
    let known = || {
        domains
            .iter()
            .map(|domain| domain.name.as_str())
            .collect::<Vec<_>>()
            .join(", ")
    };
    let Some(chosen) = chosen.map(str::trim).filter(|chosen| !chosen.is_empty()) else {
        return Err(t!("shared.deployments.no_root_domain", domains = known())
            .to_string()
            .into());
    };
    let Some(domain) = domains.iter().find(|domain| domain.name == chosen) else {
        return Err(t!(
            "shared.deployments.unknown_root_domain",
            domain = chosen,
            domains = known()
        )
        .to_string()
        .into());
    };
    domain.check(name, dns_mode).map_err(|conflict| {
        SakctlError::new(
            conflict_text(&domain.name, &conflict),
            conflict_exit_code(&conflict),
        )
    })?;
    Ok(domain.name.clone())
}

/// the dns mode and the root domain of a deployment: the given values, else for a deploy the
/// values on the labels of the deployment. A create without a mode, and a deploy without a mode
/// on the labels, use subdomain. A deploy without a root domain is refused. A destroy needs neither
pub(super) fn resolve_dns(
    name: &str,
    intent: Intent,
    given_mode: Option<DnsMode>,
    given_domain: Option<&str>,
    labeled_mode: Option<DnsMode>,
    labeled_domain: Option<String>,
) -> Result<(DnsMode, Option<String>), String> {
    let given_domain = given_domain
        .map(str::trim)
        .filter(|domain| !domain.is_empty())
        .map(str::to_string);
    match intent {
        Intent::Destroy => Ok((DnsMode::default(), None)),
        Intent::Create => Ok((given_mode.unwrap_or_default(), given_domain)),
        Intent::Deploy => {
            let domain = given_domain.or(labeled_domain).ok_or_else(|| {
                t!(
                    "shared.deployments.deploy_without_root_domain",
                    name = name,
                    hint = crate::core::i18n::hint(crate::core::i18n::Hint::RootDomain)
                )
                .to_string()
            })?;
            Ok((
                given_mode.or(labeled_mode).unwrap_or_default(),
                Some(domain),
            ))
        }
    }
}
