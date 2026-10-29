//! the sak deployments at the providers: every resource with a name label, grouped by the name
//! and diffed against the resource group of a deployment. A create, deploy or destroy of a
//! deployment, planned and applied from its name and password

use crate::core::container::ContainerRuntime;
use crate::core::error::{
    EXIT_DOMAIN_TAKEN, EXIT_MIXED_DNS_MODES, EXIT_NOT_OWNED, EXIT_RECREATE_REFUSED, EXIT_USAGE,
    WizardError,
};
use crate::core::events::{AnsibleEvent, task_name};
use crate::core::keys::DerivedKey;
use crate::core::providers::{ProviderType, ProviderTypeResponse};
use crate::core::provision::{ProvisionManager, ProvisionOutput, ProvisionRun};
use rust_i18n::t;
use sak_cloud::api::Lookup;
use sak_cloud::api::{Applied, ApplyEvent, DiffStatus};
use sak_cloud::hetzner::api::{HetznerApi, Location, ServerType};
use sak_cloud::hetzner::deployment::{
    DeploymentSpec, DeploymentState, DeploymentStatus, DiscoveredDeployment, DnsMode, MAX_NAME_LEN,
    ResourceKind, ResourceState, RootDomain, RootDomainConflict, check_name, deployment_records,
    discover, foreign_records, remove_records, root_domains, set_server_label, stale_records,
    state,
};
use sak_cloud::hetzner::dns_record::{HetznerDnsRecordLookup, first_address};
use sak_cloud::hetzner::labels::HetznerLabels;
use sak_cloud::hetzner::{PASSWORD_HASH_LABEL, PROVISIONED_CONFIG_LABEL};
use sak_cloud::providers::hetzner::HetznerProviderRuntime;
use serde::{Deserialize, Serialize};
use std::fmt;
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
    /// where its frontend is, `https://sak.<base domain>`; none without a root domain
    pub(crate) frontend_url: Option<String>,
    pub(crate) resources: Vec<ResourceState>,
}

impl DeploymentResponse {
    fn of(
        provider: ProviderType,
        state: DeploymentState,
        deployment: &DiscoveredDeployment,
    ) -> Self {
        DeploymentResponse {
            provider: provider.response(),
            name: state.name,
            status: state.status,
            location: state.location,
            dns_mode: state.dns_mode,
            root_domain: deployment.root_domain(),
            frontend_url: deployment.frontend_url(),
            resources: state.resources,
        }
    }
}

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

/// the deployments that use a root domain, `beta, gamma`; one without a name as such
fn by_text(by: &[Option<String>]) -> String {
    by.iter()
        .map(|name| match name {
            Some(name) => name.clone(),
            None => t!("shared.deployments.unnamed").to_string(),
        })
        .collect::<Vec<_>>()
        .join(", ")
}

fn conflict_text(domain: &str, conflict: &RootDomainConflict) -> String {
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
fn conflict_exit_code(conflict: &RootDomainConflict) -> i32 {
    match conflict {
        RootDomainConflict::DomainTaken { .. } => EXIT_DOMAIN_TAKEN,
        RootDomainConflict::MixedModes { .. } => EXIT_MIXED_DNS_MODES,
    }
}

/// checks the root domain `chosen` for the deployment `name` in `dns_mode`: it is a zone of the
/// project and has no conflict with the deployments that use it
fn check_root_domain(
    domains: &[RootDomain],
    chosen: Option<&str>,
    name: &str,
    dns_mode: DnsMode,
) -> Result<String, WizardError> {
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
        WizardError::new(
            conflict_text(&domain.name, &conflict),
            conflict_exit_code(&conflict),
        )
    })?;
    Ok(domain.name.clone())
}

/// the plan of a create, deploy or destroy: what the apply does, resource by resource
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
    /// `server 'alpha'`, the name the events of the apply use for it
    pub(crate) resource: String,
    /// the kind of resource, `ssh_key`, `volume`, …, `provisioning`, and its name at the
    /// provider: the page and the command line show the resource with them, in their language
    pub(crate) kind: String,
    pub(crate) name: String,
    /// `up_to_date`, `missing` or `has_changes`, the resource status of the glossary
    pub(crate) resource_status: &'static str,
    /// what the plan does to it, `will_create`, `will_update`, `will_destroy`, `will_keep`,
    /// `already_deleted`, `will_run` or `runs_when_forced`; none for a resource that stays as it is
    pub(crate) action: Option<&'static str>,
    /// what the apply does to it when it starts, `working`, `working_update`, `working_recreate`,
    /// `working_check` or `working_destroy`: the command line shows `cli.<working>`, the window
    /// `ui.plan.<working>`
    pub(crate) working: &'static str,
    pub(crate) status: DiffStatus,
    /// a destroy keeps it: the data volume, unless `delete_data` is set
    pub(crate) keep: bool,
    pub(crate) changes: Vec<String>,
}

/// the key of the text of an apply's result, for the command line and the window alike: a
/// resource created anew reads as created, one that stays as up-to-date
pub fn applied_key(applied: Applied) -> &'static str {
    match applied {
        Applied::Created | Applied::Recreated => "shared.done.created",
        Applied::Updated => "shared.done.updated",
        Applied::Unchanged => "shared.resource_status.up_to_date",
    }
}

/// the key of the text of a destroyed resource: destroyed, or gone already
pub fn destroyed_key(gone: bool) -> &'static str {
    if gone {
        "shared.done.destroyed"
    } else {
        "shared.plan.already_deleted"
    }
}

/// what an apply reports while it runs: a resource started, a resource done, the provisioning.
/// The command itself answers when everything is done or failed
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
    /// a resource removed (`gone` false: it was gone already)
    Destroyed {
        resource: String,
        gone: bool,
    },
    /// a message about the provisioning: it starts, or sakctl waits for the api
    Provisioning {
        message: String,
    },
    /// a line ansible printed while provisioning the server
    Output {
        line: String,
    },
    /// a task of the playbook begins; `resource` is the provisioning as the plan names it
    TaskStarted {
        resource: String,
        task: String,
    },
    /// a task of the playbook ended on the server; `message` is what ansible says when it failed
    TaskDone {
        resource: String,
        task: String,
        outcome: TaskOutcome,
        message: Option<String>,
    },
    /// the playbook ran to its end
    Provisioned {
        resource: String,
    },
    /// the playbook did not run: the server was not changed and is provisioned with this
    /// configuration, and the deploy was not forced
    ProvisioningUpToDate {
        resource: String,
    },
    /// the playbook failed; `task` when the events tell it
    ProvisionFailed {
        resource: String,
        task: Option<String>,
        message: String,
    },
}

/// how a task of the playbook ended on the server
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TaskOutcome {
    Ok,
    Changed,
    Skipped,
    /// failed, but the playbook continues
    Ignored,
    Failed,
    Unreachable,
}

/// the event of the apply for an event of the playbook; none for an event the apply does not show
fn task_event(resource: &str, event: AnsibleEvent) -> Option<CreateEvent> {
    let done = |task: String, outcome, message| {
        Some(CreateEvent::TaskDone {
            resource: resource.to_string(),
            task: task_name(&task),
            outcome,
            message,
        })
    };
    match event {
        AnsibleEvent::TaskStart { task } => Some(CreateEvent::TaskStarted {
            resource: resource.to_string(),
            task: task_name(&task),
        }),
        AnsibleEvent::Ok { task, changed, .. } => done(
            task,
            if changed {
                TaskOutcome::Changed
            } else {
                TaskOutcome::Ok
            },
            None,
        ),
        AnsibleEvent::Skipped { task, .. } => done(task, TaskOutcome::Skipped, None),
        AnsibleEvent::Failed {
            task,
            msg,
            ignore_errors,
            ..
        } => done(
            task,
            if ignore_errors {
                TaskOutcome::Ignored
            } else {
                TaskOutcome::Failed
            },
            msg,
        ),
        AnsibleEvent::Unreachable { task, msg, .. } => done(task, TaskOutcome::Unreachable, msg),
        AnsibleEvent::PlayStart { .. } | AnsibleEvent::Stats { .. } => None,
    }
}

/// the cloud-init of the server: it installs the derived host key, and no other, before sshd
/// first starts, so sakctl verifies the first connection against it
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

/// the values the environment proposes for a new deployment, so a script types nothing. No Debug,
/// the passwords never show in a log
#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeploymentDefaults {
    name: Option<String>,
    password: Option<String>,
    location: Option<String>,
    /// the mail server of SAK_SMTP_HOST, SAK_SMTP_PORT, SAK_SMTP_USERNAME, SAK_SMTP_PASSWORD and
    /// SAK_SMTP_STARTTLS
    smtp_host: Option<String>,
    smtp_port: Option<String>,
    smtp_username: Option<String>,
    smtp_password: Option<String>,
    smtp_starttls: Option<bool>,
    /// the admin of SAK_ADMIN_EMAIL
    admin_email: Option<String>,
    /// the user mode of SAK_USER_MODE
    user_mode: Option<UserMode>,
}

/// the variables of the name, the password and the location of a deployment, for the window and
/// the command line
pub const NAME_VARIABLE: &str = "SAK_NAME";
pub const PASSWORD_VARIABLE: &str = "SAK_PASSWORD";
pub const LOCATION_VARIABLE: &str = "SAK_LOCATION";

/// the variables of the mail server of a deployment, for the window and the command line
pub const SMTP_HOST_VARIABLE: &str = "SAK_SMTP_HOST";
pub const SMTP_PORT_VARIABLE: &str = "SAK_SMTP_PORT";
pub const SMTP_USERNAME_VARIABLE: &str = "SAK_SMTP_USERNAME";
pub const SMTP_PASSWORD_VARIABLE: &str = "SAK_SMTP_PASSWORD";
pub const SMTP_STARTTLS_VARIABLE: &str = "SAK_SMTP_STARTTLS";
/// the variable of the address of the admin, for the window and the command line
pub const ADMIN_EMAIL_VARIABLE: &str = "SAK_ADMIN_EMAIL";
/// the variable of the user mode, for the window and the command line
pub const USER_MODE_VARIABLE: &str = "SAK_USER_MODE";

/// the user mode: who signs in to a deployment. `Single`: one user, the admin, with the password
/// of the deployment. `Multi`: users with a link by e-mail, the admin also with the password; it
/// needs a mail server and the address of the admin
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum UserMode {
    Single,
    Multi,
}

/// the acme ca the certificates of a deployment come from: production (the default issuers of
/// caddy), or the staging ca of let's encrypt for the deployments of the tests, because it does not
/// have the rate limits of production
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AcmeCa {
    #[default]
    Production,
    Staging,
}

impl AcmeCa {
    pub const ALL: [AcmeCa; 2] = [AcmeCa::Production, AcmeCa::Staging];

    pub fn as_str(self) -> &'static str {
        match self {
            AcmeCa::Production => "production",
            AcmeCa::Staging => "staging",
        }
    }

    pub fn parse(value: &str) -> Option<AcmeCa> {
        AcmeCa::ALL
            .into_iter()
            .find(|ca| ca.as_str() == value.trim().to_lowercase())
    }

    /// the acme directory caddy gets the certificates from, none for its default issuers
    pub fn directory(self) -> Option<&'static str> {
        match self {
            AcmeCa::Production => None,
            AcmeCa::Staging => Some("https://acme-staging-v02.api.letsencrypt.org/directory"),
        }
    }

    /// the roots sakctl trusts when it talks to such a deployment
    pub fn trust(self) -> crate::core::config::Trust {
        match self {
            AcmeCa::Production => crate::core::config::Trust::Public,
            AcmeCa::Staging => crate::core::config::Trust::LetsEncryptStaging,
        }
    }
}

impl UserMode {
    pub const ALL: [UserMode; 2] = [UserMode::Single, UserMode::Multi];

    pub fn as_str(self) -> &'static str {
        match self {
            UserMode::Single => "single",
            UserMode::Multi => "multi",
        }
    }

    pub fn parse(value: &str) -> Option<UserMode> {
        UserMode::ALL
            .into_iter()
            .find(|mode| mode.as_str() == value.trim().to_lowercase())
    }
}

/// the longest host name of a mail server and the longest e-mail address that dns and smtp
/// allow; the fields of the page have the same limits
pub const MAX_HOST_LEN: usize = 253;
pub const MAX_ADDRESS_LEN: usize = 254;

/// the port and the starttls of a mail server when none is given, the defaults of the backend
pub const DEFAULT_SMTP_PORT: u16 = 587;
pub const DEFAULT_SMTP_STARTTLS: bool = true;

/// the mail server the backend sends its login links with. No Debug, the password never shows in
/// a log
#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Smtp {
    pub host: String,
    pub port: u16,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub password: String,
    pub starttls: bool,
}

/// checks the mail server: a host name without spaces, a port that is not 0, and no line break in
/// a value, because `backend.env` has one line for each value
fn check_smtp(smtp: &Smtp) -> Result<(), String> {
    let host = smtp.host.trim();
    if host.is_empty() || host.len() > MAX_HOST_LEN || host.contains(char::is_whitespace) {
        return Err(t!("shared.deployments.invalid_smtp_host").to_string());
    }
    if smtp.port == 0 {
        return Err(t!("shared.deployments.invalid_smtp_port").to_string());
    }
    if [smtp.username.as_str(), smtp.password.as_str()]
        .iter()
        .any(|value| value.contains(['\n', '\r']))
    {
        return Err(t!("shared.deployments.invalid_smtp_value").to_string());
    }
    Ok(())
}

/// whether `address` is an e-mail address as a deployment needs it: a local part of letters,
/// digits and `._%+-`, one `@`, a domain with a dot. Nothing that breaks a line of `backend.env`
/// or of yaml
pub fn is_address(address: &str) -> bool {
    if address.trim().len() > MAX_ADDRESS_LEN {
        return false;
    }
    let mut parts = address.trim().split('@');
    let (Some(local), Some(domain), None) = (parts.next(), parts.next(), parts.next()) else {
        return false;
    };
    !local.is_empty()
        && local
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "._%+-".contains(c))
        && domain.contains('.')
        && !domain.starts_with(['.', '-'])
        && !domain.ends_with(['.', '-'])
        && domain
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || ".-".contains(c))
}

/// `true` or `false` (also `1`/`0`, `yes`/`no`, `on`/`off`) of a variable or an argument
pub fn parse_flag(value: &str) -> Result<bool, String> {
    match value.trim().to_lowercase().as_str() {
        "true" | "1" | "yes" | "on" => Ok(true),
        "false" | "0" | "no" | "off" => Ok(false),
        _ => Err("true or false".to_string()),
    }
}

/// a location to create a deployment in, as the page and the command line offer it
#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocationResponse {
    pub(crate) id: Location,
    pub(crate) name: &'static str,
}

/// the locations of hetzner; the first is the one proposed
pub(crate) const LOCATIONS: &[LocationResponse] = &[
    LocationResponse {
        id: Location::nbg1,
        name: "Nuremberg, Germany",
    },
    LocationResponse {
        id: Location::hel1,
        name: "Helsinki, Finland",
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

/// the server type of a deployment: cx23 where hetzner offers it (the european locations), cpx21
/// in the other locations
fn server_type_for(location: Location) -> ServerType {
    match location {
        Location::fsn1 | Location::nbg1 | Location::hel1 => ServerType::cx23,
        Location::ash | Location::hil | Location::sin => ServerType::cpx21,
    }
}

/// the name of a serialized enum value, `up_to_date`, `created`: the page reads it in the json,
/// the command line uses it as the key of its texts
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
        smtp_host: variable(SMTP_HOST_VARIABLE),
        smtp_port: variable(SMTP_PORT_VARIABLE),
        smtp_username: variable(SMTP_USERNAME_VARIABLE),
        smtp_password: variable(SMTP_PASSWORD_VARIABLE),
        smtp_starttls: variable(SMTP_STARTTLS_VARIABLE).and_then(|value| parse_flag(&value).ok()),
        admin_email: variable(ADMIN_EMAIL_VARIABLE),
        user_mode: variable(USER_MODE_VARIABLE).and_then(|value| UserMode::parse(&value)),
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

/// the least number of characters of a password; every key and label of a deployment is derived
/// from it
const MIN_PASSWORD_CHARS: usize = 16;

/// what a plan or an apply is for: `Create` a deployment that does not exist yet, `Deploy` an
/// existing one to what it declares (its server created anew when necessary)
#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Intent {
    Create,
    Deploy,
    /// remove every resource of the deployment, the data volume only with `delete_data`. A
    /// deployment that is gone is destroyed already
    Destroy,
}

/// a deployment to plan and apply, as the page sends it or the command line builds it
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewDeployment {
    pub name: String,
    pub password: String,
    pub password_repeat: String,
    pub location: Location,
    /// the dns mode, kept on every resource. None: subdomain for a create, the mode of the
    /// deployment for a deploy (`resolve_dns`)
    #[serde(default)]
    pub dns_mode: Option<DnsMode>,
    /// the root domain, a dns zone of the project, kept on every resource. None: the root domain
    /// of the deployment for a deploy; a destroy needs none
    #[serde(default)]
    pub root_domain: Option<String>,
    pub intent: Intent,
    /// a destroy also deletes the data volume; without it the volume stays
    #[serde(default)]
    pub delete_data: bool,
    /// who signs in, needed by a create and a deploy; a destroy needs none
    #[serde(default)]
    pub user_mode: Option<UserMode>,
    /// where the certificates come from. None: the ca of the deployment for a deploy, production
    /// for a create
    #[serde(default)]
    pub acme_ca: Option<AcmeCa>,
    /// the mail server of the e-mail login: needed for multi user, not used for single user
    #[serde(default)]
    pub smtp: Option<Smtp>,
    /// the address of the admin, the first user of the e-mail login: needed for multi user, not
    /// used for single user
    #[serde(default)]
    pub admin_email: Option<String>,
    /// a create or deploy runs the playbook even when the provisioning is up to date: the server
    /// was not changed and was provisioned with this configuration. Without it such a provisioning
    /// is skipped
    #[serde(default)]
    pub force: bool,
}

/// the dns mode and the root domain of a deployment: the given values, else for a deploy the
/// values on the labels of the deployment. A create without a mode, and a deploy without a mode
/// on the labels, use subdomain. A deploy without a root domain is refused. A destroy needs neither
fn resolve_dns(
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
                t!("shared.deployments.deploy_without_root_domain", name = name).to_string()
            })?;
            Ok((
                given_mode.or(labeled_mode).unwrap_or_default(),
                Some(domain),
            ))
        }
    }
}

/// a deployment checked and ready to plan and apply: where it goes and what it consists of. The
/// command line plans and applies with the same one, so it reads the project one time. The window
/// prepares again for the apply, because the user can wait a long time on the plan
pub struct Prepared {
    provider: ProviderType,
    runtime: HetznerProviderRuntime,
    spec: DeploymentSpec,
    /// the dns records of the deployment that the apply removes
    stale_records: Vec<HetznerDnsRecordLookup>,
    client_key: DerivedKey,
    host_key: DerivedKey,
    /// the public half of the api key; the backend checks a signed request with it
    api_public_key: String,
    /// the admin of a multi user deployment; none for single user and on a destroy
    admin_email: Option<String>,
    /// the argon2id hash of the admin password (the deployment password)
    admin_password_hash: String,
    /// the mail server of the e-mail login; none for the password login only
    smtp: Option<Smtp>,
    /// the label `sak.pelle.io/provisioned-config` of the server after a provisioning of this
    /// configuration (empty on a destroy), and the label now (none when there is no server or it
    /// was never provisioned). The plan compares them to find a changed configuration; a deploy
    /// always provisions
    provisioned_config: String,
    provisioned: Option<String>,
    /// where the certificates come from; it sets what sakctl trusts when it waits for the api
    acme_ca: AcmeCa,
}

impl Prepared {
    /// the configuration the playbook writes, one value on each line, as the label of the server
    /// stands for it. Without the addresses and the device of the volume, a deployment keeps them
    fn config(&self) -> String {
        config_text(
            &self.spec.name,
            self.spec.dns_mode,
            self.spec.root_domain.as_deref().unwrap_or_default(),
            &self.api_public_key,
            self.admin_email.as_deref().unwrap_or_default(),
            self.smtp.as_ref(),
            &self.admin_password_hash,
        ) + &format!("\nacme_ca {}", self.acme_ca.as_str())
    }
}

/// the settings a deploy takes from the existing deployment when they are not given
/// (`DeploymentManager::detected`)
#[derive(Clone, Debug, Default, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Detected {
    pub user_mode: Option<UserMode>,
    pub admin_email: Option<String>,
    pub smtp: Option<DetectedSmtp>,
}

/// the mail server of the running configuration, with its password when the backend answers it.
/// No derived Debug, the password never shows
#[derive(Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DetectedSmtp {
    pub host: String,
    pub port: u16,
    pub username: String,
    pub password: Option<String>,
    pub password_set: bool,
    pub starttls: bool,
}

impl fmt::Debug for DetectedSmtp {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("DetectedSmtp")
            .field("host", &self.host)
            .field("port", &self.port)
            .field("username", &self.username)
            .field("password", &self.password.as_ref().map(|_| "***"))
            .field("password_set", &self.password_set)
            .field("starttls", &self.starttls)
            .finish()
    }
}

/// the user mode of the label, else of the running configuration (multi user when it has a mail
/// server); the admin and the mail server only for multi user
fn detect(
    label: Option<UserMode>,
    running: Option<&crate::core::config::RunningConfig>,
) -> Detected {
    let user_mode = label.or_else(|| {
        running.map(|config| match config.smtp {
            Some(_) => UserMode::Multi,
            None => UserMode::Single,
        })
    });
    let Some(running) = running.filter(|_| user_mode == Some(UserMode::Multi)) else {
        return Detected {
            user_mode,
            ..Detected::default()
        };
    };
    Detected {
        user_mode,
        admin_email: running.admins.first().cloned(),
        smtp: running.smtp.as_ref().map(|smtp| DetectedSmtp {
            host: smtp.host.clone(),
            port: smtp.port,
            username: smtp.username.clone().unwrap_or_default(),
            password: smtp.password.clone(),
            password_set: smtp.password_set,
            starttls: smtp.starttls,
        }),
    }
}

/// a deploy completed from `detected` where a value is not given: the user mode and, for multi
/// user, the admin and the mail server. A given `smtp_password` wins over the detected one. An
/// older backend does not answer the password; then the password must be given, because a deploy
/// without it removes it
pub fn complete(
    mut deployment: NewDeployment,
    detected: &Detected,
    smtp_password: &str,
) -> Result<NewDeployment, WizardError> {
    if deployment.user_mode.is_none() {
        deployment.user_mode = detected.user_mode;
    }
    if deployment.user_mode != Some(UserMode::Multi) {
        return Ok(deployment);
    }
    if deployment.admin_email.is_none() {
        deployment.admin_email = detected.admin_email.clone();
    }
    if deployment.smtp.is_none()
        && let Some(smtp) = &detected.smtp
    {
        let password = match (smtp_password, &smtp.password) {
            ("", Some(running)) => running.clone(),
            ("", None) if smtp.password_set => {
                return Err(WizardError::new(
                    t!("shared.deployments.smtp_password_needed"),
                    crate::core::error::EXIT_USAGE,
                ));
            }
            (given, _) => given.to_string(),
        };
        deployment.smtp = Some(Smtp {
            host: smtp.host.clone(),
            port: smtp.port,
            username: smtp.username.clone(),
            password,
            starttls: smtp.starttls,
        });
    }
    Ok(deployment)
}

/// the resource status of the glossary for a status of a diff. A resource whose parent is missing
/// is missing too. A resource found under another name stops the plan before (NotOwned)
pub fn resource_status(status: DiffStatus) -> &'static str {
    match status {
        DiffStatus::UpToDate => "up_to_date",
        DiffStatus::Missing | DiffStatus::ParentMissing => "missing",
        DiffStatus::HasChanges | DiffStatus::Duplicate => "has_changes",
    }
}

/// what the plan does to a resource, from its status. A stale dns record (`remove`) goes. A
/// destroy keeps the data volume (`keep`), leaves what is gone and removes the rest. A create or
/// deploy creates what is missing or must be created anew and updates what has changes
fn action(
    intent: Intent,
    status: DiffStatus,
    recreate: bool,
    keep: bool,
    remove: bool,
) -> Option<&'static str> {
    let missing = matches!(status, DiffStatus::Missing | DiffStatus::ParentMissing);
    match intent {
        _ if remove => Some("will_destroy"),
        Intent::Destroy if keep => Some("will_keep"),
        Intent::Destroy if missing => Some("already_deleted"),
        Intent::Destroy => Some("will_destroy"),
        _ if missing || recreate => Some("will_create"),
        _ if status == DiffStatus::UpToDate => None,
        _ => Some("will_update"),
    }
}

/// what the apply does to a resource when it starts, from what the plan found: it creates what is
/// missing, checks what is up-to-date, updates or recreates what has changes, and destroys on a
/// destroy and a stale dns record
fn working(intent: Intent, status: DiffStatus, recreate: bool, remove: bool) -> &'static str {
    match (intent, status) {
        _ if remove => "working_destroy",
        (Intent::Destroy, _) => "working_destroy",
        (_, DiffStatus::Missing | DiffStatus::ParentMissing) => "working",
        (_, DiffStatus::UpToDate) => "working_check",
        _ if recreate => "working_recreate",
        _ => "working_update",
    }
}

/// the status of the provisioning in the plan; a create or deploy always runs it. Missing for a
/// server that is created (anew) or was never provisioned. Has changes for a server with changes
/// or another configuration. Up-to-date only for a server that stays as it is and has the label
/// of this configuration
fn provisioning_status(
    provisioned: Option<&str>,
    expected: &str,
    server: Option<DiffStatus>,
    server_recreated: bool,
) -> (DiffStatus, Vec<String>) {
    let changed = |key: &str| (DiffStatus::HasChanges, vec![t!(key).to_string()]);
    match server {
        None | Some(DiffStatus::Missing | DiffStatus::ParentMissing) => {
            return (DiffStatus::Missing, Vec::new());
        }
        Some(_) if server_recreated => return (DiffStatus::Missing, Vec::new()),
        Some(DiffStatus::HasChanges) => return changed("shared.provision.server_changes"),
        Some(_) => {}
    }
    match provisioned {
        None => (DiffStatus::Missing, Vec::new()),
        Some(label) if label != expected => changed("shared.provision.config_changed"),
        Some(_) => (DiffStatus::UpToDate, Vec::new()),
    }
}

/// the values `ProvisionRun` gives the playbook to write onto the server; a change of one of them
/// is a change of the configuration
fn config_text(
    name: &str,
    dns_mode: DnsMode,
    root_domain: &str,
    api_public_key: &str,
    admin_email: &str,
    smtp: Option<&Smtp>,
    admin_password_hash: &str,
) -> String {
    let mut lines = vec![
        format!("name {name}"),
        format!("dns_mode {dns_mode}"),
        format!("root_domain {root_domain}"),
        format!("api_public_key {api_public_key}"),
        format!("admin_email {admin_email}"),
        format!("admin_password_hash {admin_password_hash}"),
    ];
    if let Some(smtp) = smtp {
        lines.push(format!("smtp_host {}", smtp.host.trim()));
        lines.push(format!("smtp_port {}", smtp.port));
        lines.push(format!("smtp_username {}", smtp.username));
        lines.push(format!("smtp_password {}", smtp.password));
        lines.push(format!("smtp_starttls {}", smtp.starttls));
    }
    lines.join("\n")
}

/// checks the password of an existing deployment before anything changes: against the hash label
/// on its resources, where every hash must match. Without a hash on a resource (created before
/// every resource carried it), against the public client key
fn verify_password(
    deployment: &DiscoveredDeployment,
    name: &str,
    password: &str,
) -> Result<(), String> {
    let (hashes, key) = password_proofs(deployment);
    check_password(&hashes, key, name, password)
}

/// where `config show` asks and with which key: the api of the base domain on the labels of the
/// deployment, and the api key of the password. sakctl checks the password first, so it signs
/// nothing with the key of a wrong password
fn config_endpoint(
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

/// a name as sakctl takes it, trimmed: 1 to MAX_NAME_LEN lower case letters, digits and dashes,
/// so hetzner and dns take it with the prefix and suffix of the cloud names. The only check of a
/// name; the window calls it when the user leaves the field. A wrong name is a usage error
/// (`EXIT_USAGE`)
pub fn checked_name(name: &str) -> Result<&str, WizardError> {
    let name = name.trim();
    check_name(name).map_err(|_| {
        WizardError::new(
            t!("shared.deployments.invalid_name", max = MAX_NAME_LEN),
            EXIT_USAGE,
        )
    })?;
    Ok(name)
}

/// what a password can be checked against: the password hashes on the resources and the public
/// key of the ssh key. Both are empty when only resources without labels are left
fn password_proofs(deployment: &DiscoveredDeployment) -> (Vec<String>, Option<&str>) {
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
fn check_password(
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

fn failed(provider: ProviderType, error: impl ToString) -> String {
    message("shared.deployments.failed", provider, error)
}

/// a failure of the provider in a plan or an apply. A resource of a declared name that belongs
/// to someone else, and a resource that must stay but would be created anew, each have an exit
/// code of their own; nothing of them was changed
fn cloud_failure(key: &str, provider: ProviderType, error: sak_cloud::api::Error) -> WizardError {
    match error {
        sak_cloud::api::Error::NotOwned { .. } => WizardError::new(
            t!("shared.deployments.not_owned", error = error.to_string()),
            EXIT_NOT_OWNED,
        ),
        sak_cloud::api::Error::RecreateRefused { .. } => WizardError::new(
            t!(
                "shared.deployments.recreate_refused",
                error = error.to_string()
            ),
            EXIT_RECREATE_REFUSED,
        ),
        error => message(key, provider, error).into(),
    }
}

/// a message about the provider: `key` names what failed (list, plan, apply)
fn message(key: &str, provider: ProviderType, error: impl ToString) -> String {
    t!(key, provider = provider.name(), error = error.to_string()).to_string()
}

/// manages the sak deployments at the providers: finds them by the name label, diffs them
/// against the resource group of a deployment, plans and applies a create, deploy or destroy.
/// Every call gets the provider tokens, so a token added later counts immediately
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
                        deployments.push(DeploymentResponse::of(provider, state, deployment));
                    }
                }
            }
        }
        Ok(deployments)
    }

    /// the settings a deploy can take from an existing deployment, for the window and the command
    /// line: the user mode on its labels and, for multi user, the admin and the mail server of its
    /// running configuration (read with a request signed with the api key). A deployment without
    /// the label is multi user when its backend has a mail server. A deployment that does not
    /// answer gives only its label
    pub async fn detected(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
        password: &str,
    ) -> Result<Detected, String> {
        let name = checked_name(name).map_err(|error| error.message)?;
        let Some((provider, token)) = tokens
            .iter()
            .find(|(provider, _)| *provider == ProviderType::Hetzner)
            .cloned()
        else {
            return Err(t!("shared.deployments.no_provider").to_string());
        };
        let label = discover(&HetznerApi::new(&token))
            .await
            .map_err(|error| failed(provider, error))?
            .into_iter()
            .find(|deployment| deployment.name == name)
            .and_then(|deployment| deployment.user_mode())
            .and_then(|mode| UserMode::parse(&mode));
        // a single user deployment has no more settings
        let running = match label {
            Some(UserMode::Single) => None,
            _ => self
                .running_config(tokens, name, password)
                .await
                .ok()
                .map(|(_, config)| config),
        };
        Ok(detect(label, running.as_ref()))
    }

    /// the api url and the running configuration of a deployment, read from its backend at
    /// `api.<base domain>` with a request signed with the api key. sakctl checks the password
    /// against the deployment first
    /// whether `password` is the one of the deployment `name`: checked against the password
    /// hashes on its resources, or its ssh key; the window asks it while the password is typed
    pub async fn check_password(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
        password: &str,
    ) -> Result<(), String> {
        let name = checked_name(name).map_err(|error| error.message)?;
        let Some((provider, token)) = tokens
            .into_iter()
            .find(|(provider, _)| *provider == ProviderType::Hetzner)
        else {
            return Err(t!("shared.deployments.no_provider").to_string());
        };
        let deployment = discover(&HetznerApi::new(&token))
            .await
            .map_err(|error| failed(provider, error))?
            .into_iter()
            .find(|deployment| deployment.name == name)
            .ok_or_else(|| t!("shared.deployments.not_found", name = name).to_string())?;
        verify_password(&deployment, name, password)
    }

    pub async fn running_config(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
        password: &str,
    ) -> Result<(String, crate::core::config::RunningConfig), String> {
        let name = checked_name(name).map_err(|error| error.message)?;
        let Some((provider, token)) = tokens
            .into_iter()
            .find(|(provider, _)| *provider == ProviderType::Hetzner)
        else {
            return Err(t!("shared.deployments.no_provider").to_string());
        };
        let api_client = HetznerApi::new(&token);
        let deployment = discover(&api_client)
            .await
            .map_err(|error| failed(provider, error))?
            .into_iter()
            .find(|deployment| deployment.name == name)
            .ok_or_else(|| t!("shared.deployments.not_found", name = name).to_string())?;
        let (url, key) = config_endpoint(&deployment, name, password)?;
        let trust = deployment
            .acme_ca()
            .and_then(|ca| AcmeCa::parse(&ca))
            .unwrap_or_default()
            .trust();
        let config = crate::core::config::fetch(&url, &key, trust).await?;
        Ok((url, config))
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
            return Err(t!("shared.deployments.no_provider").to_string());
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

    /// checks the name (free for a create, an existing deployment for a deploy), the password
    /// (long enough, the same two times, hard to guess for a create, the one of the deployment
    /// otherwise) and, but for a destroy, the root domain (a zone of the project that allows the
    /// dns mode). Then derives the keys and labels and lays out the resource group at the first
    /// hetzner project
    pub async fn prepare(
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
            user_mode,
            acme_ca,
            smtp,
            admin_email,
            force: _,
        } = deployment;
        let (location, intent) = (*location, *intent);
        let name = checked_name(name)?;
        if password.chars().count() < MIN_PASSWORD_CHARS {
            return Err(t!(
                "shared.deployments.short_password",
                min = MIN_PASSWORD_CHARS
            )
            .to_string()
            .into());
        }
        if password != password_repeat {
            return Err(t!("shared.deployments.password_mismatch")
                .to_string()
                .into());
        }
        // a new password must be hard to guess: whoever reads the project can try guesses offline
        // against the label and the public keys of the deployment
        if intent == Intent::Create
            && let Some(hint) = crate::core::keys::weak_password(name, password)
        {
            return Err(t!("shared.deployments.weak_password", hint = hint)
                .to_string()
                .into());
        }
        let Some((provider, token)) = tokens
            .into_iter()
            .find(|(provider, _)| *provider == ProviderType::Hetzner)
        else {
            return Err(t!("shared.deployments.no_provider").to_string().into());
        };
        let api = Arc::new(HetznerApi::new(&token));
        let existing = discover(&api)
            .await
            .map_err(|error| failed(provider, error))?
            .into_iter()
            .find(|deployment| deployment.name == name);
        // the user mode given, else the one on the deployment (a deploy keeps it)
        let user_mode = user_mode.or_else(|| {
            existing
                .as_ref()
                .and_then(DiscoveredDeployment::user_mode)
                .and_then(|mode| UserMode::parse(&mode))
        });
        // the acme ca given, else the one of the deployment, else production
        let acme_ca = acme_ca
            .or_else(|| {
                existing
                    .as_ref()
                    .and_then(DiscoveredDeployment::acme_ca)
                    .and_then(|ca| AcmeCa::parse(&ca))
            })
            .unwrap_or_default();
        // multi user needs the mail server and the address of the admin, single user uses neither
        let (smtp, admin_email) = match (intent, user_mode) {
            (Intent::Destroy, _) => (None, None),
            (_, None) => return Err(t!("shared.deployments.no_user_mode").to_string().into()),
            (_, Some(UserMode::Single)) => (None, None),
            (_, Some(UserMode::Multi)) => {
                let smtp = smtp
                    .clone()
                    .ok_or_else(|| t!("shared.deployments.invalid_smtp_host").to_string())?;
                check_smtp(&smtp)?;
                let admin_email = admin_email
                    .as_deref()
                    .map(str::trim)
                    .filter(|address| is_address(address))
                    .map(str::to_lowercase)
                    .ok_or_else(|| t!("shared.deployments.invalid_admin_email").to_string())?;
                (Some(smtp), Some(admin_email))
            }
        };
        let (dns_mode, root_domain) = resolve_dns(
            name,
            intent,
            *dns_mode,
            root_domain.as_deref(),
            existing.as_ref().and_then(DiscoveredDeployment::dns_mode),
            existing
                .as_ref()
                .and_then(DiscoveredDeployment::root_domain),
        )?;
        let runtime =
            HetznerProviderRuntime::from_api(api.clone(), location, server_type_for(location));
        // one read of the zones gives the root domains, the records of the deployment and the
        // records in the way
        let listings = runtime
            .dns_zones()
            .list_all()
            .await
            .map_err(|error| failed(provider, error))?;
        let records = deployment_records(&listings, name);
        // what the server was provisioned with, before `existing` is taken apart
        let provisioned = existing
            .as_ref()
            .and_then(|deployment| deployment.server.as_ref())
            .and_then(|server| server.labels.get(PROVISIONED_CONFIG_LABEL).cloned());
        let location = match (intent, existing) {
            (Intent::Create, Some(_)) => {
                return Err(t!("shared.deployments.name_taken", name = name)
                    .to_string()
                    .into());
            }
            (Intent::Create, None) => location,
            (Intent::Deploy, None) => {
                return Err(t!("shared.deployments.not_found", name = name)
                    .to_string()
                    .into());
            }
            // destroyed already, maybe with records left: check the password against them
            (Intent::Destroy, None) => {
                let hashes: Vec<String> = records
                    .iter()
                    .filter_map(|record| record.labels.get(PASSWORD_HASH_LABEL).cloned())
                    .collect();
                check_password(&hashes, None, name, password)?;
                location
            }
            (Intent::Deploy | Intent::Destroy, Some(existing)) => {
                verify_password(&existing, name, password)?;
                // the location of the deployment: its server, volume and addresses never move, so
                // the given location counts only when none of them exists
                existing
                    .location()
                    .map_err(|resources| {
                        t!(
                            "shared.deployments.mixed_locations",
                            name = name,
                            resources = resources
                        )
                        .to_string()
                    })?
                    .unwrap_or(location)
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
        let api_public_key = crate::core::keys::api_key(name, password)?.public_key();
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
        // the user mode on every resource, so a deploy keeps it
        if intent != Intent::Destroy
            && let Some(user_mode) = user_mode
        {
            spec = spec.with_user_mode(user_mode.as_str());
        }
        if intent != Intent::Destroy {
            spec = spec.with_acme_ca(acme_ca.as_str());
        }
        spec.user_data = user_data(&host_key);
        let stale_records = stale_records(&records, &spec.dns_records(), &spec.identity());
        // a record of another owner, of any type, on a name of the deployment stops the apply
        // here, before anything is created
        let in_the_way = foreign_records(&listings, &spec);
        if intent != Intent::Destroy && !in_the_way.is_empty() {
            return Err(WizardError::new(
                t!(
                    "shared.deployments.not_owned",
                    error = t!(
                        "shared.deployments.records_in_the_way",
                        records = in_the_way.join(", ")
                    )
                ),
                EXIT_NOT_OWNED,
            ));
        }
        let mut prepared = Prepared {
            provider,
            runtime,
            spec,
            stale_records,
            client_key,
            host_key,
            api_public_key,
            admin_email,
            admin_password_hash: crate::core::keys::admin_password_hash(name, password)?,
            smtp,
            provisioned_config: String::new(),
            provisioned,
            acme_ca,
        };
        if intent != Intent::Destroy {
            prepared.provisioned_config =
                crate::core::keys::provisioned_config(name, password, &prepared.config())?;
        }
        Ok(prepared)
    }

    /// prepares the deployment and plans it: its resources diffed against the project
    pub async fn plan(
        &self,
        tokens: Vec<(ProviderType, String)>,
        deployment: &NewDeployment,
    ) -> Result<PlanResponse, WizardError> {
        let prepared = self.prepare(tokens, deployment).await?;
        self.plan_prepared(&prepared, deployment).await
    }

    /// the plan of a deployment prepared already
    pub async fn plan_prepared(
        &self,
        prepared: &Prepared,
        deployment: &NewDeployment,
    ) -> Result<PlanResponse, WizardError> {
        let volume = prepared.spec.data_volume().name;
        let mut registry = prepared
            .spec
            .registry(&prepared.runtime)
            .map_err(|error| error.to_string())?;
        let diffs = registry.diff_all().await.map_err(|error| {
            cloud_failure("shared.deployments.plan_failed", prepared.provider, error)
        })?;
        Ok(PlanResponse {
            name: prepared.spec.name.clone(),
            public_key: prepared.spec.public_key.clone(),
            resources: diffs
                .iter()
                .map(|diff| {
                    let keep = deployment.intent == Intent::Destroy
                        && !deployment.delete_data
                        && diff.resource.name == volume;
                    PlannedResource {
                        resource: diff.resource.log_text.clone(),
                        kind: ResourceKind::of(&diff.resource)
                            .map(|kind| key(&kind))
                            .unwrap_or_default(),
                        name: diff.resource.name.clone(),
                        resource_status: resource_status(diff.status),
                        action: action(
                            deployment.intent,
                            diff.status,
                            diff.needs_recreate(),
                            keep,
                            false,
                        ),
                        working: working(
                            deployment.intent,
                            diff.status,
                            diff.needs_recreate(),
                            false,
                        ),
                        status: diff.status,
                        keep,
                        // a destroy removes everything, so the changes are of no interest
                        changes: match deployment.intent {
                            Intent::Destroy => Vec::new(),
                            Intent::Create | Intent::Deploy => diff
                                .changes
                                .iter()
                                .map(|change| change.log_text())
                                .collect(),
                        },
                    }
                })
                .chain(prepared.stale_records.iter().map(|record| PlannedResource {
                    resource: record.log_text(),
                    kind: key(&ResourceKind::DnsRecord),
                    name: record.resource_ref().name,
                    resource_status: resource_status(DiffStatus::UpToDate),
                    action: action(deployment.intent, DiffStatus::UpToDate, false, false, true),
                    working: working(deployment.intent, DiffStatus::UpToDate, false, true),
                    status: DiffStatus::UpToDate,
                    keep: false,
                    changes: Vec::new(),
                }))
                // a create or deploy provisions the server after its resources
                .chain((deployment.intent != Intent::Destroy).then(|| {
                    let server = prepared.spec.resource_name();
                    let server = diffs.iter().find(|diff| diff.resource.name == server);
                    let (status, changes) = provisioning_status(
                        prepared.provisioned.as_deref(),
                        &prepared.provisioned_config,
                        server.map(|diff| diff.status),
                        server.is_some_and(|diff| diff.needs_recreate()),
                    );
                    PlannedResource {
                        resource: t!(
                            "shared.provision.resource",
                            name = prepared.spec.resource_name()
                        )
                        .to_string(),
                        kind: "provisioning".to_string(),
                        name: prepared.spec.resource_name(),
                        resource_status: resource_status(status),
                        // the playbook runs when the provisioning is not up to date, an up to date
                        // one only on a forced deploy
                        action: Some(if status == DiffStatus::UpToDate && !deployment.force {
                            "runs_when_forced"
                        } else {
                            "will_run"
                        }),
                        working: working(deployment.intent, status, false, false),
                        status,
                        keep: false,
                        changes,
                    }
                }))
                .collect(),
        })
    }

    /// prepares the deployment and applies it. A create or deploy applies the resources in the
    /// order of their dependencies, then removes the dns records of an old dns mode or root domain.
    /// Then ansible provisions the server, sakctl waits for the api and sets the provisioned-config
    /// label. Each resource, line and task goes to `on_event`. What is up-to-date stays as it is,
    /// so the next run finishes a run that failed. A destroy removes the dns records of the
    /// deployment, then every resource but the data volume (also that with `delete_data`),
    /// dependents first
    pub async fn create(
        &self,
        tokens: Vec<(ProviderType, String)>,
        deployment: &NewDeployment,
        container: &ContainerRuntime,
        on_event: &mut (dyn FnMut(CreateEvent) + Send),
    ) -> Result<(), WizardError> {
        let prepared = self.prepare(tokens, deployment).await?;
        self.apply(prepared, deployment, container, on_event).await
    }

    /// applies a deployment prepared already, as `create` does
    pub async fn apply(
        &self,
        prepared: Prepared,
        deployment: &NewDeployment,
        container: &ContainerRuntime,
        on_event: &mut (dyn FnMut(CreateEvent) + Send),
    ) -> Result<(), WizardError> {
        if deployment.intent == Intent::Destroy {
            self.remove_records(&prepared, on_event)
                .await
                .map_err(|error| {
                    cloud_failure(
                        "shared.deployments.destroy_failed",
                        prepared.provider,
                        error,
                    )
                })?;
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
                .map_err(|error| {
                    cloud_failure(
                        "shared.deployments.destroy_failed",
                        prepared.provider,
                        error,
                    )
                })?;
            return Ok(());
        }
        let mut registry = prepared
            .spec
            .registry(&prepared.runtime)
            .map_err(|error| error.to_string())?;
        // a server created or created anew needs its provisioning, whatever its label says
        let server_name = prepared.spec.resource_name();
        let mut server_changed = false;
        let mut report = |event: ApplyEvent| {
            let event = match event {
                ApplyEvent::Started(resource) => CreateEvent::Started {
                    resource: resource.log_text,
                },
                ApplyEvent::Applied(result) => {
                    if ResourceKind::of(&result.resource) == Some(ResourceKind::Server)
                        && result.resource.name == server_name
                        && matches!(result.applied, Applied::Created | Applied::Recreated)
                    {
                        server_changed = true;
                    }
                    CreateEvent::Applied {
                        resource: result.resource.log_text,
                        applied: result.applied,
                    }
                }
                ApplyEvent::Destroyed(..) => return,
            };
            on_event(event);
        };
        registry
            .apply_all_with(&mut report)
            .await
            .map_err(|error| {
                cloud_failure("shared.deployments.create_failed", prepared.provider, error)
            })?;
        self.remove_records(&prepared, on_event)
            .await
            .map_err(|error| {
                cloud_failure("shared.deployments.create_failed", prepared.provider, error)
            })?;

        // the result: the address of the server and the device of its data volume
        let created = discover(&prepared.runtime.api)
            .await
            .map_err(|error| failed(prepared.provider, error))?
            .into_iter()
            .find(|found| found.name == prepared.spec.name)
            .ok_or_else(|| {
                t!("shared.deployments.not_found", name = prepared.spec.name).to_string()
            })?;
        let server = created.server.as_ref().ok_or_else(|| {
            t!("shared.deployments.not_found", name = prepared.spec.name).to_string()
        })?;
        // the provisioning as the plan names it, its events carry it. A forced deploy always runs
        // the playbook, so the server is as the deployment declares it, whatever happened to it;
        // otherwise only when the server changed or was provisioned with another configuration
        let resource = t!(
            "shared.provision.resource",
            name = prepared.spec.resource_name()
        )
        .to_string();
        if !deployment.force
            && !server_changed
            && prepared.provisioned.as_deref() == Some(prepared.provisioned_config.as_str())
        {
            on_event(CreateEvent::ProvisioningUpToDate { resource });
            return Ok(());
        }
        let server_id = server.id;
        let provider = prepared.provider;
        let api = prepared.runtime.api.clone();
        let provisioned_config = prepared.provisioned_config.clone();
        // remove the label first: after a run that fails or is stopped the plan shows the
        // provisioning as missing
        set_server_label(&api, server_id, PROVISIONED_CONFIG_LABEL, None)
            .await
            .map_err(|error| failed(prepared.provider, error))?;
        let host = server.public_ipv4.clone().ok_or_else(|| {
            t!("shared.provision.no_address", name = prepared.spec.name).to_string()
        })?;
        let volume_name = prepared.spec.data_volume().name;
        let storage_device = created
            .volumes
            .iter()
            .find(|volume| volume.name == volume_name)
            .map(|volume| volume.device.clone())
            .ok_or_else(|| {
                t!("shared.provision.no_volume", name = prepared.spec.name).to_string()
            })?;
        // the server just provisioned; the wait for the api goes to this address
        let address = host.parse::<std::net::IpAddr>().ok();
        let run = ProvisionRun {
            name: prepared.spec.name.clone(),
            dns_mode: prepared.spec.dns_mode,
            // a create or deploy has one, prepare checked it
            root_domain: prepared.spec.root_domain.clone().unwrap_or_default(),
            api_public_key: prepared.api_public_key.clone(),
            // a create or deploy has them, prepare checked them
            admin_email: prepared.admin_email.clone().unwrap_or_default(),
            smtp: prepared.smtp.clone(),
            admin_password_hash: prepared.admin_password_hash.clone(),
            acme_ca: prepared.acme_ca.directory().map(String::from),
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
                "shared.provision.running",
                name = run.name.as_str(),
                host = run.host.as_str()
            )
            .to_string(),
        });
        // ansible runs as a process of its own; its lines come through a channel while it runs
        let runtime = container.clone();
        let (sender, mut receiver) = tokio::sync::mpsc::unbounded_channel();
        let provisioning = tokio::task::spawn_blocking(move || {
            ProvisionManager.provision(&runtime, &run, &mut |output| {
                let _ = sender.send(output);
            })
        });
        let mut failed_task = None;
        while let Some(output) = receiver.recv().await {
            let event = match output {
                ProvisionOutput::Line(line) => Some(CreateEvent::Output { line }),
                ProvisionOutput::Event(event) => task_event(&resource, event),
            };
            if let Some(CreateEvent::TaskDone {
                task,
                outcome: TaskOutcome::Failed | TaskOutcome::Unreachable,
                ..
            }) = &event
            {
                failed_task.get_or_insert_with(|| task.clone());
            }
            if let Some(event) = event {
                on_event(event);
            }
        }
        if let Err(message) = provisioning.await.map_err(|error| error.to_string())? {
            on_event(CreateEvent::ProvisionFailed {
                resource,
                task: failed_task,
                message: message.clone(),
            });
            return Err(message.into());
        }
        on_event(CreateEvent::Provisioned { resource });
        // done when the deployment answers as its users reach it: through its ingress, with its
        // certificate. A backend that did not start or a missing certificate stops the apply here
        if let Some(root_domain) = &prepared.spec.root_domain {
            let api = crate::core::config::api_url(&sak_cloud::hetzner::deployment::base_domain(
                &prepared.spec.name,
                prepared.spec.dns_mode,
                root_domain,
            ));
            on_event(CreateEvent::Provisioning {
                message: t!("shared.deployments.waiting_for_api", url = api.as_str()).to_string(),
            });
            crate::core::config::wait_for_api(
                &api,
                address,
                prepared.acme_ca.trust(),
                crate::core::config::API_WAIT,
                std::time::Duration::from_secs(5),
            )
            .await?;
        }
        set_server_label(
            &api,
            server_id,
            PROVISIONED_CONFIG_LABEL,
            Some(&provisioned_config),
        )
        .await
        .map_err(|error| failed(provider, error))?;
        Ok(())
    }

    /// removes the stale dns records of the deployment and reports each when it starts and when
    /// it is done
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

#[cfg(test)]
mod window_fixtures;

#[cfg(test)]
mod tests {
    use super::*;
    use sak_cloud::hetzner::{DNS_MODE_LABEL, ROOT_DOMAIN_LABEL};

    #[test]
    fn the_start_of_an_apply_says_what_is_done_to_the_resource() {
        assert_eq!(
            working(Intent::Create, DiffStatus::Missing, false, false),
            "working"
        );
        assert_eq!(
            working(Intent::Deploy, DiffStatus::HasChanges, false, false),
            "working_update"
        );
        assert_eq!(
            working(Intent::Deploy, DiffStatus::HasChanges, true, false),
            "working_recreate"
        );
        assert_eq!(
            working(Intent::Deploy, DiffStatus::UpToDate, false, false),
            "working_check"
        );
        assert_eq!(
            working(Intent::Destroy, DiffStatus::UpToDate, false, false),
            "working_destroy"
        );
        // a dns record of the old dns mode goes on a deploy
        assert_eq!(
            working(Intent::Deploy, DiffStatus::UpToDate, false, true),
            "working_destroy"
        );
    }

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

    /// a deployment of `alpha` whose server carries the password hash of `password` and the labels
    fn deployment_with(password: &str, labels: serde_json::Value) -> DiscoveredDeployment {
        let mut labels = labels;
        labels[PASSWORD_HASH_LABEL] =
            serde_json::json!(crate::core::keys::password_hash("alpha", password).unwrap());
        let server: sak_cloud::hetzner::api::servers::Server =
            serde_json::from_value(serde_json::json!({
                "id": 1, "name": "sak-alpha", "status": "running", "server_type": { "id": 1, "name": "cx23" },
                "image": { "id": 2, "name": "debian-13" }, "location": { "id": 3, "name": "nbg1" },
                "protection": { "delete": false, "rebuild": false }, "volumes": [],
                "public_net": { "ipv4": null, "ipv6": null }, "private_net": [],
                "labels": labels
            }))
            .unwrap();
        DiscoveredDeployment {
            name: "alpha".to_string(),
            server: Some(server.into()),
            ..Default::default()
        }
    }

    #[test]
    fn config_show_reads_the_host_from_the_root_domain_and_dns_mode_labels() {
        let password = "a password of sixteen";
        let domain = deployment_with(
            password,
            serde_json::json!({ ROOT_DOMAIN_LABEL: "pelle.io", DNS_MODE_LABEL: "domain" }),
        );
        let subdomain = deployment_with(
            password,
            serde_json::json!({ ROOT_DOMAIN_LABEL: "pelle.io", DNS_MODE_LABEL: "subdomain" }),
        );

        let (url, key) = config_endpoint(&domain, "alpha", password).unwrap();
        assert_eq!(url, "https://api.pelle.io");
        assert_eq!(
            key.public_key(),
            crate::core::keys::api_key("alpha", password)
                .unwrap()
                .public_key()
        );
        assert_eq!(
            config_endpoint(&subdomain, "alpha", password).unwrap().0,
            "https://api.alpha.pelle.io"
        );
    }

    #[test]
    fn config_show_refuses_a_wrong_password_before_any_request() {
        let deployment = deployment_with(
            "a password of sixteen",
            serde_json::json!({ ROOT_DOMAIN_LABEL: "pelle.io", DNS_MODE_LABEL: "domain" }),
        );
        // no address and no key come out of it, so nothing is sent
        assert_eq!(
            config_endpoint(&deployment, "alpha", "another password here").err(),
            Some(t!("shared.deployments.wrong_password", name = "alpha").to_string())
        );
    }

    #[test]
    fn config_show_without_a_root_domain_label_says_so() {
        let password = "a password of sixteen";
        let deployment = deployment_with(password, serde_json::json!({ DNS_MODE_LABEL: "domain" }));
        assert_eq!(
            config_endpoint(&deployment, "alpha", password).err(),
            Some(t!("shared.config.no_root_domain", name = "alpha").to_string())
        );
    }

    #[test]
    fn config_show_refuses_a_deployment_with_nothing_to_check_the_password_against() {
        let deployment = DiscoveredDeployment {
            name: "alpha".to_string(),
            ..Default::default()
        };
        assert_eq!(
            config_endpoint(&deployment, "alpha", "a password of sixteen").err(),
            Some(t!("shared.deployments.unverifiable", name = "alpha").to_string())
        );
    }

    #[test]
    fn a_name_is_checked_once_and_a_wrong_one_is_a_usage_error() {
        assert_eq!(checked_name(" alpha-1 "), Ok("alpha-1"));
        for wrong in ["", "Alpha", "-alpha", "a_b", &"a".repeat(MAX_NAME_LEN + 1)] {
            assert_eq!(
                checked_name(wrong),
                Err(WizardError::new(
                    t!("shared.deployments.invalid_name", max = MAX_NAME_LEN),
                    EXIT_USAGE
                )),
                "{wrong}"
            );
        }
    }

    #[test]
    fn a_task_is_shown_without_its_role() {
        assert_eq!(
            task_name("pelle_io.sak.controller : Install the backend"),
            "Install the backend"
        );
        assert_eq!(task_name("Gathering Facts"), "Gathering Facts");
        assert!(matches!(
            task_event(
                "row",
                AnsibleEvent::TaskStart {
                    task: "pelle_io.sak.common : Install nftables".into()
                }
            ),
            Some(CreateEvent::TaskStarted { task, .. }) if task == "Install nftables"
        ));
    }

    fn running(
        smtp_password: Option<&str>,
        password_set: bool,
    ) -> crate::core::config::RunningConfig {
        crate::core::config::RunningConfig {
            smtp: Some(crate::core::config::RunningSmtp {
                host: "smtp.example.org".into(),
                port: 465,
                username: Some("user".into()),
                password_set,
                password: smtp_password.map(String::from),
                from: "noreply@pelle.io".into(),
                tls: true,
                starttls: false,
            }),
            admins: vec!["admin@example.org".into()],
        }
    }

    fn deploy() -> NewDeployment {
        NewDeployment {
            name: "alpha".into(),
            password: "p".into(),
            password_repeat: "p".into(),
            location: Location::nbg1,
            dns_mode: None,
            root_domain: None,
            intent: Intent::Deploy,
            delete_data: false,
            user_mode: None,
            acme_ca: None,
            smtp: None,
            admin_email: None,
            force: true,
        }
    }

    #[test]
    fn the_user_mode_is_detected_from_the_label_else_from_the_running_configuration() {
        // the label wins
        assert_eq!(
            detect(Some(UserMode::Single), Some(&running(None, false))).user_mode,
            Some(UserMode::Single)
        );
        // without it: a mail server makes it multi user, none single user, nothing running nothing
        let multi = detect(None, Some(&running(Some("secret"), true)));
        assert_eq!(multi.user_mode, Some(UserMode::Multi));
        assert_eq!(multi.admin_email.as_deref(), Some("admin@example.org"));
        let smtp = multi.smtp.unwrap();
        assert_eq!(smtp.password.as_deref(), Some("secret"));
        // the password never shows, not in a {:?} either
        assert!(!format!("{smtp:?}").contains("secret"));
        assert!(!format!("{:?}", running(Some("secret"), true)).contains("secret"));
        let without_mail = crate::core::config::RunningConfig {
            smtp: None,
            admins: vec![],
        };
        assert_eq!(
            detect(None, Some(&without_mail)).user_mode,
            Some(UserMode::Single)
        );
        assert_eq!(detect(None, None), Detected::default());
        // single user: no admin and no mail server even when the backend has them
        assert_eq!(
            detect(Some(UserMode::Single), Some(&running(None, false))).smtp,
            None
        );
    }

    #[test]
    fn a_deploy_takes_what_it_is_not_told_from_the_detected_settings() {
        let detected = detect(Some(UserMode::Multi), Some(&running(Some("secret"), true)));

        let completed = complete(deploy(), &detected, "").unwrap();
        assert_eq!(completed.user_mode, Some(UserMode::Multi));
        assert_eq!(completed.admin_email.as_deref(), Some("admin@example.org"));
        let smtp = completed.smtp.unwrap();
        assert_eq!(
            (
                smtp.host.as_str(),
                smtp.port,
                smtp.username.as_str(),
                smtp.password.as_str()
            ),
            ("smtp.example.org", 465, "user", "secret")
        );
        // what is given wins: the mode, the admin, the password of the mail server
        let mut single = deploy();
        single.user_mode = Some(UserMode::Single);
        assert!(complete(single, &detected, "").unwrap().smtp.is_none());
        let mut own = deploy();
        own.admin_email = Some("other@example.org".into());
        assert_eq!(
            complete(own, &detected, "").unwrap().admin_email.as_deref(),
            Some("other@example.org")
        );
        assert_eq!(
            complete(deploy(), &detected, "given")
                .unwrap()
                .smtp
                .unwrap()
                .password,
            "given"
        );
        // a backend of before tells no password: it has to be given
        let before = detect(Some(UserMode::Multi), Some(&running(None, true)));
        assert_eq!(
            complete(deploy(), &before, "")
                .err()
                .map(|error| error.exit_code),
            Some(crate::core::error::EXIT_USAGE)
        );
    }

    #[test]
    fn the_tasks_of_the_playbook_show_on_the_row_of_the_provisioning() {
        let row = "provisioning 'sak-alpha'";
        let done = |event| match task_event(row, event) {
            Some(CreateEvent::TaskDone {
                resource,
                outcome,
                message,
                ..
            }) => {
                assert_eq!(resource, row);
                (outcome, message)
            }
            _ => panic!("no task done"),
        };
        let ok = |changed| AnsibleEvent::Ok {
            host: "alpha".into(),
            task: "t".into(),
            changed,
        };
        assert_eq!(done(ok(false)), (TaskOutcome::Ok, None));
        assert_eq!(done(ok(true)), (TaskOutcome::Changed, None));
        let failed = |ignore_errors| AnsibleEvent::Failed {
            host: "alpha".into(),
            task: "t".into(),
            msg: Some("no".into()),
            ignore_errors,
        };
        assert_eq!(
            done(failed(false)),
            (TaskOutcome::Failed, Some("no".into()))
        );
        assert_eq!(
            done(failed(true)),
            (TaskOutcome::Ignored, Some("no".into()))
        );
        assert!(matches!(
            task_event(row, AnsibleEvent::TaskStart { task: "t".into() }),
            Some(CreateEvent::TaskStarted { task, .. }) if task == "t"
        ));
        assert!(task_event(row, AnsibleEvent::PlayStart { play: "p".into() }).is_none());
        // the window reads the events by these names
        assert_eq!(
            serde_json::to_value(CreateEvent::Provisioned {
                resource: row.into()
            })
            .unwrap()["event"],
            "provisioned"
        );
        assert_eq!(
            serde_json::to_value(TaskOutcome::Unreachable).unwrap(),
            "unreachable"
        );
    }

    #[test]
    fn the_plan_tells_whether_the_configuration_of_the_server_changed() {
        let up = Some(DiffStatus::UpToDate);
        // provisioned with this configuration, the server stays: up to date
        assert_eq!(
            provisioning_status(Some("a"), "a", up, false),
            (DiffStatus::UpToDate, vec![])
        );
        // another configuration, a changing server: changes, and why
        assert_eq!(
            provisioning_status(Some("b"), "a", up, false),
            (
                DiffStatus::HasChanges,
                vec![t!("shared.provision.config_changed").to_string()]
            )
        );
        assert_eq!(
            provisioning_status(Some("a"), "a", Some(DiffStatus::HasChanges), false),
            (
                DiffStatus::HasChanges,
                vec![t!("shared.provision.server_changes").to_string()]
            )
        );
        // never provisioned, a new server, one created anew: the first run
        assert_eq!(
            provisioning_status(None, "a", up, false).0,
            DiffStatus::Missing
        );
        assert_eq!(
            provisioning_status(Some("a"), "a", None, false).0,
            DiffStatus::Missing
        );
        assert_eq!(
            provisioning_status(Some("a"), "a", Some(DiffStatus::HasChanges), true).0,
            DiffStatus::Missing
        );
    }

    #[test]
    fn the_configuration_of_the_label_holds_what_the_playbook_writes() {
        let smtp = Smtp {
            host: " smtp.example.org ".to_string(),
            port: 465,
            username: "user".to_string(),
            password: "secret".to_string(),
            starttls: false,
        };
        let text = config_text(
            "alpha",
            DnsMode::Domain,
            "pelle.io",
            "abc",
            "admin@example.org",
            Some(&smtp),
            "$argon2id$hash",
        );
        for part in [
            "name alpha",
            "dns_mode domain",
            "root_domain pelle.io",
            "api_public_key abc",
            "admin_email admin@example.org",
            "admin_password_hash $argon2id$hash",
            "smtp_host smtp.example.org",
            "smtp_port 465",
            "smtp_password secret",
            "smtp_starttls false",
        ] {
            assert!(text.lines().any(|line| line == part), "{part}");
        }
        // single user: no mail server, another configuration
        assert_ne!(
            config_text(
                "alpha",
                DnsMode::Domain,
                "pelle.io",
                "abc",
                "",
                None,
                "$argon2id$hash"
            ),
            text
        );
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
        // nothing to check against, a data volume left without a hash: refused, not any password
        assert_eq!(
            check_password(&[], None, name, password),
            Err(t!("shared.deployments.unverifiable", name = name).to_string())
        );
    }

    #[test]
    fn the_user_data_installs_the_host_key() {
        let host_key =
            crate::core::keys::derive_host_key("alpha", "a password of sixteen").unwrap();
        let user_data = user_data(&host_key);
        assert!(user_data.starts_with("#cloud-config\nssh_deletekeys: true\nssh_genkeytypes: []\nssh_keys:\n  ed25519_private: |\n    -----BEGIN OPENSSH PRIVATE KEY-----\n"));
        assert!(user_data.ends_with(&format!("  ed25519_public: {}\n", host_key.public_key())));
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
            t!("shared.deployments.no_zones")
        );
        assert_eq!(
            en(check_root_domain(
                &domains(),
                None,
                "alpha",
                DnsMode::Subdomain
            )),
            t!(
                "shared.deployments.no_root_domain",
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
                "shared.deployments.no_root_domain",
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
                "shared.deployments.unknown_root_domain",
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
                t!(
                    "shared.deployments.domain_taken",
                    domain = "pelle.io",
                    by = "beta"
                ),
                EXIT_DOMAIN_TAKEN
            ))
        );
        assert_eq!(
            check_root_domain(&domains(), Some("pelle.io"), "alpha", DnsMode::Subdomain),
            Err(WizardError::new(
                t!(
                    "shared.deployments.domain_mixed",
                    domain = "pelle.io",
                    by = "beta"
                ),
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
        let by = format!("gamma, {}", t!("shared.deployments.unnamed"));
        assert_eq!(
            check_root_domain(&domains(), Some("example.com"), "alpha", DnsMode::Domain),
            Err(WizardError::new(
                t!(
                    "shared.deployments.subdomain_only",
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
            Some(
                t!(
                    "shared.deployments.domain_taken",
                    domain = "pelle.io",
                    by = "beta"
                )
                .as_ref()
            )
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

    fn smtp(host: &str, port: u16) -> Smtp {
        Smtp {
            host: host.to_string(),
            port,
            username: "user".to_string(),
            password: "secret".to_string(),
            starttls: true,
        }
    }

    #[test]
    fn an_address_is_plain_enough_for_backend_env_and_yaml() {
        for address in [
            "admin@example.org",
            "first.last+sak@mail.example.co.uk",
            " a@b.de ",
        ] {
            assert!(is_address(address), "{address}");
        }
        for address in [
            "",
            "admin",
            "@example.org",
            "admin@",
            "admin@example",
            "a@b@example.org",
            "admin@.example.org",
            "admin@example.org.",
            "ad min@example.org",
            "admin@example.org\nSAK_X=1",
            "admin:x@example.org",
            &format!("{}@example.org", "a".repeat(MAX_ADDRESS_LEN)),
        ] {
            assert!(!is_address(address), "{address:?}");
        }
    }

    #[test]
    fn a_user_mode_is_single_or_multi() {
        assert_eq!(UserMode::parse("single"), Some(UserMode::Single));
        assert_eq!(UserMode::parse(" Multi "), Some(UserMode::Multi));
        assert_eq!(UserMode::parse("many"), None);
    }

    #[test]
    fn a_value_with_a_line_break_is_refused() {
        let mut with_break = smtp("smtp.example.org", 587);
        with_break.password = "secret\nSAK_ADMIN_PASSWORD_HASH=x".to_string();
        assert_eq!(
            check_smtp(&with_break),
            Err(t!("shared.deployments.invalid_smtp_value").to_string())
        );
    }

    #[test]
    fn a_mail_server_needs_a_host_and_a_port() {
        assert!(check_smtp(&smtp("smtp.example.org", 587)).is_ok());
        assert!(check_smtp(&smtp(" smtp.example.org ", 465)).is_ok());
        assert_eq!(
            check_smtp(&smtp("", 587)),
            Err(t!("shared.deployments.invalid_smtp_host").to_string())
        );
        assert!(check_smtp(&smtp("smtp example.org", 587)).is_err());
        assert!(check_smtp(&smtp(&"a".repeat(MAX_HOST_LEN + 1), 587)).is_err());
        assert_eq!(
            check_smtp(&smtp("smtp.example.org", 0)),
            Err(t!("shared.deployments.invalid_smtp_port").to_string())
        );
    }

    #[test]
    fn a_flag_is_true_or_false_in_the_usual_spellings() {
        for value in ["true", "TRUE", "1", "yes", "on"] {
            assert_eq!(parse_flag(value), Ok(true), "{value}");
        }
        for value in ["false", "0", "no", "Off"] {
            assert_eq!(parse_flag(value), Ok(false), "{value}");
        }
        assert!(parse_flag("maybe").is_err());
    }

    #[test]
    fn the_page_gets_the_mail_server_of_the_environment() {
        let defaults = DeploymentDefaults {
            name: None,
            password: None,
            location: None,
            smtp_host: Some("smtp.example.org".to_string()),
            smtp_port: Some("465".to_string()),
            smtp_username: Some("user".to_string()),
            smtp_password: Some("secret".to_string()),
            smtp_starttls: Some(false),
            admin_email: Some("admin@example.org".to_string()),
            user_mode: Some(UserMode::Multi),
        };
        let json = serde_json::to_value(&defaults).unwrap();
        assert_eq!(json["userMode"], "multi");
        assert_eq!(json["smtpHost"], "smtp.example.org");
        assert_eq!(json["smtpPort"], "465");
        assert_eq!(json["smtpStarttls"], false);
        // and reads it back from the page, the user name and password may be left out
        let page: Smtp = serde_json::from_value(serde_json::json!({
            "host": "smtp.example.org", "port": 587, "starttls": true
        }))
        .unwrap();
        assert_eq!((page.port, page.username.as_str()), (587, ""));
    }

    #[test]
    fn a_resource_of_someone_else_ends_with_its_own_exit_code() {
        let not_owned = cloud_failure(
            "shared.deployments.destroy_failed",
            ProviderType::Hetzner,
            sak_cloud::api::Error::NotOwned {
                resource: "server 'web'".to_string(),
                reason: "it has no label 'sak.pelle.io/name'".to_string(),
            },
        );
        assert_eq!(not_owned.exit_code, EXIT_NOT_OWNED);
        assert!(not_owned.message.starts_with(
            "server 'web' exists and does not belong to this deployment: it has no label 'sak.pelle.io/name'"
        ));
        let refused = cloud_failure(
            "shared.deployments.create_failed",
            ProviderType::Hetzner,
            sak_cloud::api::Error::RecreateRefused {
                resource: "volume 'web-data'".to_string(),
                changes: "location should be 'hel1' but was 'nbg1'".to_string(),
            },
        );
        assert_eq!(refused.exit_code, EXIT_RECREATE_REFUSED);
        assert!(
            refused
                .message
                .starts_with("volume 'web-data' would have to be created anew")
        );
        // any other failure of the provider is a failure as before
        let other = cloud_failure(
            "shared.deployments.destroy_failed",
            ProviderType::Hetzner,
            sak_cloud::api::Error::message("timeout"),
        );
        assert_eq!(other.exit_code, crate::core::error::EXIT_FAILED);
        assert!(other.message.contains("timeout"));
    }

    #[test]
    fn a_deploy_keeps_the_dns_mode_and_root_domain_of_the_deployment_unless_given() {
        let labeled = || Some("pelle.io".to_string());
        // a deploy without them keeps what the deployment carries: no switch to subdomain
        assert_eq!(
            resolve_dns(
                "beta",
                Intent::Deploy,
                None,
                None,
                Some(DnsMode::Domain),
                labeled()
            ),
            Ok((DnsMode::Domain, labeled()))
        );
        // given, they change it
        assert_eq!(
            resolve_dns(
                "beta",
                Intent::Deploy,
                Some(DnsMode::Subdomain),
                Some("example.com"),
                Some(DnsMode::Domain),
                labeled()
            ),
            Ok((DnsMode::Subdomain, Some("example.com".to_string())))
        );
        // a deployment from before the labels: subdomain, and the root domain must be given
        assert_eq!(
            resolve_dns("beta", Intent::Deploy, None, Some(" pelle.io "), None, None),
            Ok((DnsMode::Subdomain, labeled()))
        );
        assert_eq!(
            resolve_dns("beta", Intent::Deploy, None, None, None, None),
            Err(t!(
                "shared.deployments.deploy_without_root_domain",
                name = "beta"
            )
            .to_string())
        );
        assert!(resolve_dns("beta", Intent::Deploy, None, Some("  "), None, None).is_err());
    }

    #[test]
    fn a_create_is_a_subdomain_one_unless_given_and_a_destroy_needs_neither() {
        assert_eq!(
            resolve_dns("alpha", Intent::Create, None, Some("pelle.io"), None, None),
            Ok((DnsMode::Subdomain, Some("pelle.io".to_string())))
        );
        assert_eq!(
            resolve_dns(
                "alpha",
                Intent::Create,
                Some(DnsMode::Domain),
                None,
                None,
                None
            ),
            Ok((DnsMode::Domain, None))
        );
        assert_eq!(
            resolve_dns(
                "alpha",
                Intent::Destroy,
                None,
                None,
                Some(DnsMode::Domain),
                None
            ),
            Ok((DnsMode::Subdomain, None))
        );
    }

    #[test]
    fn the_server_type_follows_the_location() {
        assert_eq!(server_type_for(Location::hel1), ServerType::cx23);
        assert_eq!(server_type_for(Location::sin), ServerType::cpx21);
        assert_eq!(LOCATIONS[0].id, Location::nbg1);
    }
}
