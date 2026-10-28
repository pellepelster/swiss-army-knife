//! the sak deployments found at the providers: everything labeled with a sak name, grouped by
//! it and diffed against the resource group a deployment consists of; and a new one, planned
//! and created from its name and password

use crate::core::container::ContainerRuntime;
use crate::core::error::{EXIT_DOMAIN_TAKEN, EXIT_MIXED_DNS_MODES, WizardError};
use crate::core::keys::DerivedKey;
use crate::core::providers::{ProviderType, ProviderTypeResponse};
use crate::core::provision::{ProvisionManager, ProvisionRun};
use rust_i18n::t;
use sak_cloud::api::Lookup;
use sak_cloud::api::{Applied, ApplyEvent, DiffStatus};
use sak_cloud::hetzner::PASSWORD_HASH_LABEL;
use sak_cloud::hetzner::api::{HetznerApi, Location, ServerType};
use sak_cloud::hetzner::deployment::{
    DeploymentSpec, DeploymentState, DeploymentStatus, DiscoveredDeployment, DnsMode,
    ResourceState, RootDomain, RootDomainConflict, check_name, deployment_records, discover,
    foreign_records, remove_records, root_domains, stale_records, state,
};
use sak_cloud::hetzner::dns_record::HetznerDnsRecordLookup;
use sak_cloud::hetzner::labels::HetznerLabels;
use sak_cloud::providers::hetzner::HetznerProviderRuntime;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeploymentResponse {
    pub(crate) provider: ProviderTypeResponse,
    pub(crate) name: String,
    pub(crate) status: DeploymentStatus,
    pub(crate) location: Option<Location>,
    /// the dns mode on its resources, none for a deployment created before it was kept
    pub(crate) dns_mode: Option<DnsMode>,
    /// the root domain on its resources, none for a deployment created before it was kept
    pub(crate) root_domain: Option<String>,
    pub(crate) resources: Vec<ResourceState>,
}

impl DeploymentResponse {
    fn of(provider: ProviderType, state: DeploymentState, root_domain: Option<String>) -> Self {
        DeploymentResponse {
            provider: provider.response(),
            name: state.name,
            status: state.status,
            location: state.location,
            dns_mode: state.dns_mode,
            root_domain,
            resources: state.resources,
        }
    }
}

/// a dns zone of the project as the page offers it as root domain: the dns modes the
/// deployment can use it in, none when another deployment manages it in domain mode, and why
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RootDomainResponse {
    pub(crate) name: String,
    pub(crate) dns_modes: Vec<DnsMode>,
    /// why a mode is not possible, none when both are
    pub(crate) note: Option<String>,
}

/// the root domain as the deployment `name` can use it
fn root_domain_response(domain: &RootDomain, name: &str) -> RootDomainResponse {
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

/// the deployments using a root domain, `beta, gamma`; one without a sak name as such
fn by_text(by: &[Option<String>]) -> String {
    by.iter()
        .map(|name| match name {
            Some(name) => name.clone(),
            None => t!("deployments.unnamed").to_string(),
        })
        .collect::<Vec<_>>()
        .join(", ")
}

fn conflict_text(domain: &str, conflict: &RootDomainConflict) -> String {
    let (key, by) = match conflict {
        RootDomainConflict::DomainTaken { by } => ("deployments.domain_taken", by),
        RootDomainConflict::MixedModes {
            used: DnsMode::Domain,
            by,
        } => ("deployments.domain_mixed", by),
        RootDomainConflict::MixedModes {
            used: DnsMode::Subdomain,
            by,
        } => ("deployments.subdomain_only", by),
    };
    t!(key, domain = domain, by = by_text(by)).to_string()
}

/// the exit code of the command line for a root domain that cannot be used
fn conflict_exit_code(conflict: &RootDomainConflict) -> i32 {
    match conflict {
        RootDomainConflict::DomainTaken { .. } => EXIT_DOMAIN_TAKEN,
        RootDomainConflict::MixedModes { .. } => EXIT_MIXED_DNS_MODES,
    }
}

/// the root domain `chosen` checked for the deployment `name` in `dns_mode`: one of the
/// zones of the project, and no conflict with the deployments using it
fn check_root_domain(
    domains: &[RootDomain],
    chosen: Option<&str>,
    name: &str,
    dns_mode: DnsMode,
) -> Result<String, WizardError> {
    if domains.is_empty() {
        return Err(t!("deployments.no_zones").to_string().into());
    }
    let known = || {
        domains
            .iter()
            .map(|domain| domain.name.as_str())
            .collect::<Vec<_>>()
            .join(", ")
    };
    let Some(chosen) = chosen.map(str::trim).filter(|chosen| !chosen.is_empty()) else {
        return Err(t!("deployments.no_root_domain", domains = known())
            .to_string()
            .into());
    };
    let Some(domain) = domains.iter().find(|domain| domain.name == chosen) else {
        return Err(t!(
            "deployments.unknown_root_domain",
            domain = chosen,
            domains = known()
        )
        .to_string()
        .into());
    };
    domain.check(name, dns_mode).map_err(|conflict| {
        WizardError::new(
            conflict_text(&domain.name, &conflict),
            conflict_exit_code(&conflict),
        )
    })?;
    Ok(domain.name.clone())
}

/// the plan of a new deployment: what creating it would do, resource by resource
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanResponse {
    pub(crate) name: String,
    pub(crate) public_key: String,
    pub(crate) resources: Vec<PlannedResource>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlannedResource {
    /// `server 'alpha'`
    pub(crate) resource: String,
    pub(crate) status: DiffStatus,
    pub(crate) recreate: bool,
    /// a destroy leaves it: the data volume, unless asked to delete it
    pub(crate) keep: bool,
    /// a dns record of the deployment that goes: all of them on a destroy, those of the old
    /// dns mode or root domain on a deploy
    pub(crate) remove: bool,
    pub(crate) changes: Vec<String>,
}

/// what creating a deployment reports as it goes: a resource taken up, a resource done; the
/// command itself answers when everything is done or failed
#[derive(Clone, Serialize)]
#[serde(tag = "event", rename_all = "camelCase")]
pub enum CreateEvent {
    Started {
        resource: String,
    },
    Applied {
        resource: String,
        applied: Applied,
    },
    /// by a destroy: the resource is gone, or was gone already
    Destroyed {
        resource: String,
        gone: bool,
    },
    /// the provisioning of the server begins, what the wizard says about it
    Provisioning {
        message: String,
    },
    /// a line ansible printed while provisioning the server
    Output {
        line: String,
    },
}

/// the cloud-init of the server: the host key derived from the password installed before
/// sshd first starts, and only that one, so the first connection is verified against it
pub fn user_data(host_key: &DerivedKey) -> String {
    let private = host_key
        .private_key()
        .lines()
        .map(|line| format!("    {line}"))
        .collect::<Vec<_>>()
        .join("\n");
    format!(
        "#cloud-config\nssh_deletekeys: true\nssh_genkeytypes: []\nssh_keys:\n  ed25519_private: |\n{private}\n  ed25519_public: {public}\n",
        public = host_key.public_key()
    )
}

/// what the environment proposes for a new deployment: SAK_NAME, SAK_PASSWORD and
/// SAK_LOCATION, so a scripted run types nothing; no Debug, the password never shows up
/// in a log
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeploymentDefaults {
    name: Option<String>,
    password: Option<String>,
    location: Option<String>,
}

/// the variables a new deployment takes its name, password and location from, the pages
/// and the command line alike
pub const NAME_VARIABLE: &str = "SAK_NAME";
pub const PASSWORD_VARIABLE: &str = "SAK_PASSWORD";
pub const LOCATION_VARIABLE: &str = "SAK_LOCATION";

/// a location to create a deployment in, as the page and the command line offer it
#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocationResponse {
    pub(crate) id: Location,
    pub(crate) name: &'static str,
}

/// hetzner's locations; the first is the one proposed
pub(crate) const LOCATIONS: &[LocationResponse] = &[
    LocationResponse {
        id: Location::hel1,
        name: "Helsinki, Finland",
    },
    LocationResponse {
        id: Location::nbg1,
        name: "Nuremberg, Germany",
    },
    LocationResponse {
        id: Location::fsn1,
        name: "Falkenstein, Germany",
    },
    LocationResponse {
        id: Location::ash,
        name: "Ashburn, USA",
    },
    LocationResponse {
        id: Location::hil,
        name: "Hillsboro, USA",
    },
    LocationResponse {
        id: Location::sin,
        name: "Singapore",
    },
];

/// the server type of a deployment: cx23 where hetzner offers it, the european
/// locations, cpx21 elsewhere
fn server_type_for(location: Location) -> ServerType {
    match location {
        Location::fsn1 | Location::nbg1 | Location::hel1 => ServerType::cx23,
        Location::ash | Location::hil | Location::sin => ServerType::cpx21,
    }
}

/// the name of a serialized enum value, `up_to_date`, `created`: what the page reads in the
/// json, the command line in its texts
pub(crate) fn key<T: Serialize>(value: &T) -> String {
    serde_json::to_value(value)
        .ok()
        .and_then(|value| value.as_str().map(str::to_string))
        .unwrap_or_default()
}

/// what the environment proposes for a new deployment
pub fn defaults() -> DeploymentDefaults {
    DeploymentDefaults {
        name: variable(NAME_VARIABLE),
        password: variable(PASSWORD_VARIABLE),
        location: variable(LOCATION_VARIABLE),
    }
}

/// the locations a deployment can be created in
pub fn locations() -> Vec<LocationResponse> {
    LOCATIONS.to_vec()
}

fn variable(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

/// the password of a deployment, the ssh key is derived from it
const MIN_PASSWORD_CHARS: usize = 16;

/// what a plan or an apply is for: a deployment that does not exist yet, or one that does
/// and is brought to what the wizard declares, its server created anew when it must be
#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Intent {
    Create,
    Deploy,
    /// every resource of the deployment removed, the data volume only when asked to; a
    /// deployment that is gone is destroyed already
    Destroy,
}

/// what the page sends for a deployment to plan or create
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewDeployment {
    pub name: String,
    pub password: String,
    pub password_repeat: String,
    pub location: Location,
    /// how dns is set up for the deployment, kept on every resource; a deploy may change it
    pub dns_mode: DnsMode,
    /// the dns zone of the project the deployment's names are in, kept on every resource; a
    /// destroy needs none
    #[serde(default)]
    pub root_domain: Option<String>,
    pub intent: Intent,
    /// a destroy deletes the data volume too, it keeps it otherwise
    #[serde(default)]
    pub delete_data: bool,
}

/// a deployment checked and ready: where it goes, what it consists of, whether a resource
/// may be created anew
struct Prepared {
    provider: ProviderType,
    runtime: HetznerProviderRuntime,
    spec: DeploymentSpec,
    recreate: bool,
    /// the dns records of the deployment that go
    stale_records: Vec<HetznerDnsRecordLookup>,
    client_key: DerivedKey,
    host_key: DerivedKey,
}

/// the password of an existing deployment, checked before anything of it is changed: against
/// the hash on each of its resources, every one that carries it has to match; without a hash on
/// any of them (created before every resource carried it), against the key derived from it
fn verify_password(
    deployment: &DiscoveredDeployment,
    name: &str,
    password: &str,
) -> Result<(), String> {
    let mut labels = Vec::new();
    labels.extend(deployment.server.iter().map(|server| &server.labels));
    labels.extend(deployment.ssh_key.iter().map(|key| &key.labels));
    labels.extend(deployment.firewall.iter().map(|firewall| &firewall.labels));
    labels.extend(deployment.volumes.iter().map(|volume| &volume.labels));
    labels.extend(deployment.primary_ips.iter().map(|ip| &ip.labels));
    let hashes: Vec<String> = labels
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
    check_password(&hashes, key, name, password)
}

/// `hashes` the password hashes found on the resources, `key` the public key of the ssh key
fn check_password(
    hashes: &[String],
    key: Option<&str>,
    name: &str,
    password: &str,
) -> Result<(), String> {
    let wrong = || t!("deployments.wrong_password", name = name).to_string();
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
    }
    Ok(())
}

fn failed(provider: ProviderType, error: impl ToString) -> String {
    message("deployments.failed", provider, error)
}

/// a message about the provider: `key` names what failed, listing, planning or creating
fn message(key: &str, provider: ProviderType, error: impl ToString) -> String {
    t!(key, provider = provider.name(), error = error.to_string()).to_string()
}

/// manages the sak deployments at the providers: finds them by the name label and diffs
/// them against the resource group a deployment consists of, plans and creates new ones.
/// The tokens come from the provider tokens of the app at every call, a token added later
/// counts right away
#[derive(Default)]
pub struct DeploymentManager;

impl DeploymentManager {
    /// the deployments at every provider with a token, by provider and name
    pub async fn list(
        &self,
        tokens: Vec<(ProviderType, String)>,
    ) -> Result<Vec<DeploymentResponse>, String> {
        let mut deployments = Vec::new();
        for (provider, token) in tokens {
            match provider {
                ProviderType::Hetzner => {
                    let api = Arc::new(HetznerApi::new(&token));
                    let runtime = HetznerProviderRuntime::from_api(
                        api.clone(),
                        Location::nbg1,
                        ServerType::cx23,
                    );
                    let discovered = discover(&api)
                        .await
                        .map_err(|error| failed(provider, error))?;
                    for deployment in &discovered {
                        let state = state(&runtime, deployment)
                            .await
                            .map_err(|error| failed(provider, error))?;
                        deployments.push(DeploymentResponse::of(
                            provider,
                            state,
                            deployment.root_domain(),
                        ));
                    }
                }
            }
        }
        Ok(deployments)
    }

    /// the dns zones of the first hetzner project, each with the dns modes the deployment
    /// `name` can use it in
    pub async fn root_domains(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
    ) -> Result<Vec<RootDomainResponse>, String> {
        let Some((provider, token)) = tokens
            .into_iter()
            .find(|(provider, _)| *provider == ProviderType::Hetzner)
        else {
            return Err(t!("deployments.no_provider").to_string());
        };
        let runtime = HetznerProviderRuntime::from_api(
            Arc::new(HetznerApi::new(&token)),
            Location::nbg1,
            ServerType::cx23,
        );
        let domains = root_domains(&runtime)
            .await
            .map_err(|error| failed(provider, error))?;
        Ok(domains
            .iter()
            .map(|domain| root_domain_response(domain, name.trim()))
            .collect())
    }

    /// checks the name (a sak name no deployment of the project carries yet) and the
    /// password (long enough, typed twice the same) and, but for a destroy, the root domain
    /// (a zone of the project the dns mode is possible in), derives the ssh key and the
    /// password hash and lays out the resource group at the first hetzner project
    async fn prepare(
        &self,
        tokens: Vec<(ProviderType, String)>,
        deployment: &NewDeployment,
    ) -> Result<Prepared, WizardError> {
        let NewDeployment {
            name,
            password,
            password_repeat,
            location,
            dns_mode,
            root_domain,
            intent,
            delete_data: _,
        } = deployment;
        let (location, dns_mode, intent) = (*location, *dns_mode, *intent);
        let name = name.trim();
        check_name(name).map_err(|_| t!("deployments.invalid_name").to_string())?;
        if password.chars().count() < MIN_PASSWORD_CHARS {
            return Err(t!("deployments.short_password", min = MIN_PASSWORD_CHARS)
                .to_string()
                .into());
        }
        if password != password_repeat {
            return Err(t!("deployments.password_mismatch").to_string().into());
        }
        let Some((provider, token)) = tokens
            .into_iter()
            .find(|(provider, _)| *provider == ProviderType::Hetzner)
        else {
            return Err(t!("deployments.no_provider").to_string().into());
        };
        let api = Arc::new(HetznerApi::new(&token));
        let existing = discover(&api)
            .await
            .map_err(|error| failed(provider, error))?
            .into_iter()
            .find(|deployment| deployment.name == name);
        let runtime =
            HetznerProviderRuntime::from_api(api.clone(), location, server_type_for(location));
        // one look at the zones: the root domains, the records of the deployment, those in the way
        let listings = runtime
            .dns_zones()
            .list_all()
            .await
            .map_err(|error| failed(provider, error))?;
        let records = deployment_records(&listings, name);
        let (location, recreate) = match (intent, existing) {
            (Intent::Create, Some(_)) => {
                return Err(t!("deployments.name_taken", name = name).to_string().into());
            }
            (Intent::Create, None) => (location, false),
            (Intent::Deploy, None) => {
                return Err(t!("deployments.not_found", name = name).to_string().into());
            }
            // destroyed already, but for records left of it: its password checked against them
            (Intent::Destroy, None) => {
                let hashes: Vec<String> = records
                    .iter()
                    .filter_map(|record| record.labels.get(PASSWORD_HASH_LABEL).cloned())
                    .collect();
                check_password(&hashes, None, name, password)?;
                (location, false)
            }
            (Intent::Deploy | Intent::Destroy, Some(existing)) => {
                verify_password(&existing, name, password)?;
                // the server stays where it is
                let location = existing
                    .server
                    .as_ref()
                    .map(|server| server.location)
                    .unwrap_or(location);
                (location, intent == Intent::Deploy)
            }
        };
        let runtime =
            HetznerProviderRuntime::from_api(api.clone(), location, server_type_for(location));
        let root_domain = match intent {
            Intent::Destroy => None,
            Intent::Create | Intent::Deploy => {
                let domains: Vec<RootDomain> = listings.iter().map(RootDomain::of).collect();
                Some(check_root_domain(
                    &domains,
                    root_domain.as_deref(),
                    name,
                    dns_mode,
                )?)
            }
        };

        let client_key = crate::core::keys::derive_key(name, password)?;
        let host_key = crate::core::keys::derive_host_key(name, password)?;
        let public_key = client_key.public_key();
        let password_hash = crate::core::keys::password_hash(name, password)?;
        let mut spec = DeploymentSpec::new(
            name,
            &public_key,
            runtime.default_location,
            runtime.default_server_type,
        )
        .map_err(|error| error.to_string())?
        .with_dns_mode(dns_mode)
        .with_password_hash(&password_hash);
        if let Some(root_domain) = &root_domain {
            spec = spec.with_root_domain(root_domain);
        }
        spec.user_data = user_data(&host_key);
        let foreign = foreign_records(&listings, &spec.dns_records());
        if !foreign.is_empty() {
            let names: Vec<String> = foreign.iter().map(|record| record.log_text()).collect();
            return Err(t!(
                "deployments.foreign_records",
                name = name,
                records = names.join(", ")
            )
            .to_string()
            .into());
        }
        let stale_records = stale_records(&records, &spec.dns_records());
        Ok(Prepared {
            provider,
            runtime,
            spec,
            recreate,
            stale_records,
            client_key,
            host_key,
        })
    }

    /// what creating the deployment would do: its resources diffed against the project
    pub async fn plan(
        &self,
        tokens: Vec<(ProviderType, String)>,
        deployment: &NewDeployment,
    ) -> Result<PlanResponse, WizardError> {
        let prepared = self.prepare(tokens, deployment).await?;
        let volume = prepared.spec.data_volume().name;
        let mut registry = prepared
            .spec
            .registry(&prepared.runtime)
            .map_err(|error| error.to_string())?;
        let diffs = registry
            .diff_all()
            .await
            .map_err(|error| message("deployments.plan_failed", prepared.provider, error))?;
        Ok(PlanResponse {
            name: prepared.spec.name.clone(),
            public_key: prepared.spec.public_key.clone(),
            resources: diffs
                .iter()
                .map(|diff| PlannedResource {
                    resource: diff.resource.log_text.clone(),
                    status: diff.status,
                    recreate: diff.needs_recreate(),
                    keep: deployment.intent == Intent::Destroy
                        && !deployment.delete_data
                        && diff.resource.name == volume,
                    remove: false,
                    // everything goes on a destroy, what would change is of no interest
                    changes: match deployment.intent {
                        Intent::Destroy => Vec::new(),
                        Intent::Create | Intent::Deploy => diff
                            .changes
                            .iter()
                            .map(|change| change.log_text())
                            .collect(),
                    },
                })
                .chain(prepared.stale_records.iter().map(|record| PlannedResource {
                    resource: record.log_text(),
                    status: DiffStatus::UpToDate,
                    recreate: false,
                    keep: false,
                    remove: true,
                    changes: Vec::new(),
                }))
                .collect(),
        })
    }

    /// creates or deploys the deployment: the resources applied in the order of their
    /// dependencies, each reported to `on_event` as it is taken up and done, then the server
    /// provisioned with ansible in the image of the tools, every line it prints reported too.
    /// What exists already is left as it is, so a run that failed half way is finished by the
    /// next. The dns records of the old dns mode or root domain go after the apply. A destroy
    /// removes the dns records of the deployment, then every resource but the data volume
    /// (that too with `delete_data`), dependents first
    pub async fn create(
        &self,
        tokens: Vec<(ProviderType, String)>,
        deployment: &NewDeployment,
        container: &ContainerRuntime,
        on_event: &mut (dyn FnMut(CreateEvent) + Send),
    ) -> Result<(), WizardError> {
        let prepared = self.prepare(tokens, deployment).await?;
        if deployment.intent == Intent::Destroy {
            self.remove_records(&prepared, on_event)
                .await
                .map_err(|error| message("deployments.destroy_failed", prepared.provider, error))?;
            let mut registry = prepared
                .spec
                .destroy_registry(&prepared.runtime, deployment.delete_data)
                .map_err(|error| error.to_string())?;
            let mut report = |event: ApplyEvent| {
                let event = match event {
                    ApplyEvent::Started(resource) => CreateEvent::Started {
                        resource: resource.log_text,
                    },
                    ApplyEvent::Destroyed(resource, gone) => CreateEvent::Destroyed {
                        resource: resource.log_text,
                        gone,
                    },
                    ApplyEvent::Applied(_) => return,
                };
                on_event(event);
            };
            registry
                .destroy_all_with(&mut report)
                .await
                .map_err(|error| message("deployments.destroy_failed", prepared.provider, error))?;
            return Ok(());
        }
        let mut registry = prepared
            .spec
            .registry(&prepared.runtime)
            .map_err(|error| error.to_string())?;
        let mut report = |event: ApplyEvent| {
            let event = match event {
                ApplyEvent::Started(resource) => CreateEvent::Started {
                    resource: resource.log_text,
                },
                ApplyEvent::Applied(result) => CreateEvent::Applied {
                    resource: result.resource.log_text,
                    applied: result.applied,
                },
                ApplyEvent::Destroyed(..) => return,
            };
            on_event(event);
        };
        registry
            .apply_all_with(prepared.recreate, &mut report)
            .await
            .map_err(|error| message("deployments.create_failed", prepared.provider, error))?;
        self.remove_records(&prepared, on_event)
            .await
            .map_err(|error| message("deployments.create_failed", prepared.provider, error))?;

        // what came out of it: the server's address and the device of its data volume
        let created = discover(&prepared.runtime.api)
            .await
            .map_err(|error| failed(prepared.provider, error))?
            .into_iter()
            .find(|found| found.name == prepared.spec.name)
            .ok_or_else(|| t!("deployments.not_found", name = prepared.spec.name).to_string())?;
        let server = created
            .server
            .as_ref()
            .ok_or_else(|| t!("deployments.not_found", name = prepared.spec.name).to_string())?;
        let host = server
            .public_ipv4
            .clone()
            .ok_or_else(|| t!("provision.no_address", name = prepared.spec.name).to_string())?;
        let volume_name = prepared.spec.data_volume().name;
        let storage_device = created
            .volumes
            .iter()
            .find(|volume| volume.name == volume_name)
            .map(|volume| volume.device.clone())
            .ok_or_else(|| t!("provision.no_volume", name = prepared.spec.name).to_string())?;
        let run = ProvisionRun {
            name: prepared.spec.name.clone(),
            host,
            host_ipv6: server
                .public_ipv6
                .clone()
                .map(|network| first_address(&network)),
            client_key: prepared.client_key,
            host_key: prepared.host_key,
            storage_device,
        };

        on_event(CreateEvent::Provisioning {
            message: t!(
                "provision.running",
                name = run.name.as_str(),
                host = run.host.as_str()
            )
            .to_string(),
        });
        // ansible runs as a process of its own, its lines come through a channel while it does
        let runtime = container.clone();
        let (sender, mut receiver) = tokio::sync::mpsc::unbounded_channel();
        let provisioning = tokio::task::spawn_blocking(move || {
            ProvisionManager.provision(&runtime, &run, &mut |line| {
                let _ = sender.send(line);
            })
        });
        while let Some(line) = receiver.recv().await {
            on_event(CreateEvent::Output { line });
        }
        Ok(provisioning.await.map_err(|error| error.to_string())??)
    }

    /// removes the stale dns records of the deployment, each reported as it is taken up and
    /// done
    async fn remove_records(
        &self,
        prepared: &Prepared,
        on_event: &mut (dyn FnMut(CreateEvent) + Send),
    ) -> sak_cloud::api::Result<()> {
        for record in &prepared.stale_records {
            on_event(CreateEvent::Started {
                resource: record.log_text(),
            });
        }
        remove_records(
            &prepared.runtime,
            &prepared.stale_records,
            &mut |record, gone| {
                on_event(CreateEvent::Destroyed {
                    resource: record.log_text(),
                    gone,
                })
            },
        )
        .await
    }
}

/// `2001:db8::/64` -> `2001:db8::1`, the address of the server in its network
fn first_address(network: &str) -> String {
    let prefix = network.split('/').next().unwrap_or(network);
    match prefix.strip_suffix("::") {
        Some(head) => format!("{head}::1"),
        None => prefix.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_password_of_a_deployment_is_checked_against_its_server_or_its_key() {
        let name = "alpha";
        let password = "a password of sixteen";
        let hash = crate::core::keys::password_hash(name, password).unwrap();
        let key = crate::core::keys::derive_public_key(name, password).unwrap();
        let server: sak_cloud::hetzner::api::servers::Server =
            serde_json::from_value(serde_json::json!({
                "id": 1, "name": name, "status": "running", "server_type": { "id": 1, "name": "cx23" },
                "image": { "id": 2, "name": "debian-13" }, "location": { "id": 3, "name": "hel1" },
                "protection": { "delete": false, "rebuild": false }, "volumes": [],
                "public_net": { "ipv4": null, "ipv6": null }, "private_net": [],
                "labels": { PASSWORD_HASH_LABEL: hash }
            }))
            .unwrap();
        let blob = key.split_whitespace().take(2).collect::<Vec<_>>().join(" ");
        let ssh_key: sak_cloud::hetzner::api::ssh_keys::SshKey =
            serde_json::from_value(serde_json::json!({
                "id": 7, "name": name, "fingerprint": "aa", "public_key": blob, "labels": {}
            }))
            .unwrap();

        let with_server = DiscoveredDeployment {
            name: name.to_string(),
            server: Some(server.into()),
            ..Default::default()
        };
        assert!(verify_password(&with_server, name, password).is_ok());
        assert!(verify_password(&with_server, name, "another password here").is_err());

        let with_key = DiscoveredDeployment {
            name: name.to_string(),
            ssh_key: Some(ssh_key.into()),
            ..Default::default()
        };
        assert!(verify_password(&with_key, name, password).is_ok());
        assert!(verify_password(&with_key, name, "another password here").is_err());
    }

    #[test]
    fn every_hash_on_the_resources_has_to_match() {
        let (name, password) = ("alpha", "a password of sixteen");
        let hash = crate::core::keys::password_hash(name, password).unwrap();
        let other = crate::core::keys::password_hash(name, "another password here").unwrap();
        assert!(check_password(&[hash.clone(), hash.clone()], None, name, password).is_ok());
        // one resource of another password is enough to stop
        assert!(check_password(&[hash.clone(), other], None, name, password).is_err());
        // a hash wins over the key, a key of another password is not asked
        assert!(check_password(&[hash], Some("ssh-ed25519 AAAA"), name, password).is_ok());
        // nothing to check against: a data volume left without a hash
        assert!(check_password(&[], None, name, password).is_ok());
    }

    #[test]
    fn the_user_data_installs_the_host_key() {
        let host_key =
            crate::core::keys::derive_host_key("alpha", "a password of sixteen").unwrap();
        let user_data = user_data(&host_key);
        assert!(user_data.starts_with("#cloud-config\nssh_deletekeys: true\nssh_genkeytypes: []\nssh_keys:\n  ed25519_private: |\n    -----BEGIN OPENSSH PRIVATE KEY-----\n"));
        assert!(user_data.ends_with(&format!("  ed25519_public: {}\n", host_key.public_key())));
        assert_eq!(first_address("2001:db8::/64"), "2001:db8::1");
    }

    fn uses(name: &str, uses: &[(Option<&str>, DnsMode)]) -> RootDomain {
        RootDomain {
            name: name.to_string(),
            uses: uses
                .iter()
                .map(
                    |(name, dns_mode)| sak_cloud::hetzner::deployment::DomainUse {
                        name: name.map(str::to_string),
                        dns_mode: *dns_mode,
                    },
                )
                .collect(),
        }
    }

    /// pelle.io managed by beta as domain, example.com shared by subdomain deployments,
    /// free.org used by nobody
    fn domains() -> Vec<RootDomain> {
        vec![
            uses("pelle.io", &[(Some("beta"), DnsMode::Domain)]),
            uses(
                "example.com",
                &[
                    (Some("gamma"), DnsMode::Subdomain),
                    (None, DnsMode::Subdomain),
                ],
            ),
            uses("free.org", &[]),
        ]
    }

    #[test]
    fn a_root_domain_must_be_a_zone_of_the_project() {
        // a failure of its own, not one of the root domain conflicts
        let en = |result: Result<String, WizardError>| {
            let error = result.unwrap_err();
            assert_eq!(error.exit_code, crate::core::error::EXIT_FAILED);
            error.message
        };
        assert_eq!(
            en(check_root_domain(
                &[],
                Some("pelle.io"),
                "alpha",
                DnsMode::Subdomain
            )),
            t!("deployments.no_zones")
        );
        assert_eq!(
            en(check_root_domain(
                &domains(),
                None,
                "alpha",
                DnsMode::Subdomain
            )),
            t!(
                "deployments.no_root_domain",
                domains = "pelle.io, example.com, free.org"
            )
        );
        assert_eq!(
            en(check_root_domain(
                &domains(),
                Some("  "),
                "alpha",
                DnsMode::Subdomain
            )),
            t!(
                "deployments.no_root_domain",
                domains = "pelle.io, example.com, free.org"
            )
        );
        assert_eq!(
            en(check_root_domain(
                &domains(),
                Some("other.net"),
                "alpha",
                DnsMode::Subdomain
            )),
            t!(
                "deployments.unknown_root_domain",
                domain = "other.net",
                domains = "pelle.io, example.com, free.org"
            )
        );
        assert_eq!(
            check_root_domain(&domains(), Some(" free.org "), "alpha", DnsMode::Domain),
            Ok("free.org".to_string())
        );
    }

    #[test]
    fn a_root_domain_of_a_domain_deployment_stops_every_other_deployment() {
        assert_eq!(
            check_root_domain(&domains(), Some("pelle.io"), "alpha", DnsMode::Domain),
            Err(WizardError::new(
                t!("deployments.domain_taken", domain = "pelle.io", by = "beta"),
                EXIT_DOMAIN_TAKEN
            ))
        );
        assert_eq!(
            check_root_domain(&domains(), Some("pelle.io"), "alpha", DnsMode::Subdomain),
            Err(WizardError::new(
                t!("deployments.domain_mixed", domain = "pelle.io", by = "beta"),
                EXIT_MIXED_DNS_MODES
            ))
        );
        // beta itself deploys again, or changes to subdomain
        for mode in DnsMode::ALL {
            assert!(check_root_domain(&domains(), Some("pelle.io"), "beta", mode).is_ok());
        }
    }

    #[test]
    fn a_root_domain_of_subdomain_deployments_takes_only_the_subdomain_mode() {
        let by = format!("gamma, {}", t!("deployments.unnamed"));
        assert_eq!(
            check_root_domain(&domains(), Some("example.com"), "alpha", DnsMode::Domain),
            Err(WizardError::new(
                t!(
                    "deployments.subdomain_only",
                    domain = "example.com",
                    by = by
                ),
                EXIT_MIXED_DNS_MODES
            ))
        );
        assert_eq!(
            check_root_domain(&domains(), Some("example.com"), "alpha", DnsMode::Subdomain),
            Ok("example.com".to_string())
        );
    }

    #[test]
    fn the_page_gets_the_modes_of_each_root_domain_and_why_one_is_not_possible() {
        let responses: Vec<RootDomainResponse> = domains()
            .iter()
            .map(|domain| root_domain_response(domain, "alpha"))
            .collect();
        assert!(responses[0].dns_modes.is_empty());
        assert_eq!(
            responses[0].note.as_deref(),
            Some(t!("deployments.domain_taken", domain = "pelle.io", by = "beta").as_ref())
        );
        assert_eq!(responses[1].dns_modes, vec![DnsMode::Subdomain]);
        assert!(responses[1].note.is_some());
        assert_eq!(responses[2].dns_modes, DnsMode::ALL.to_vec());
        assert_eq!(responses[2].note, None);
        assert_eq!(
            serde_json::to_value(&responses[1]).unwrap(),
            serde_json::json!({
                "name": "example.com",
                "dnsModes": ["subdomain"],
                "note": responses[1].note,
            })
        );
    }

    #[test]
    fn the_server_type_follows_the_location() {
        assert_eq!(server_type_for(Location::hel1), ServerType::cx23);
        assert_eq!(server_type_for(Location::sin), ServerType::cpx21);
        assert_eq!(LOCATIONS[0].id, Location::hel1);
    }
}
