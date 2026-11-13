//! what a create, deploy or destroy is asked with: the deployment, its users and mail server, the
//! values of the environment, and their checks

use super::*;

/// the values the environment proposes for a new deployment. No Debug: it holds passwords
#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeploymentDefaults {
    pub(super) name: Option<String>,
    pub(super) password: Option<String>,
    pub(super) location: Option<String>,
    /// the mail server of SAK_SMTP_HOST, SAK_SMTP_PORT, SAK_SMTP_USERNAME, SAK_SMTP_PASSWORD,
    /// SAK_SMTP_STARTTLS and SAK_SMTP_FROM
    pub(super) smtp_host: Option<String>,
    pub(super) smtp_port: Option<String>,
    pub(super) smtp_username: Option<String>,
    pub(super) smtp_password: Option<String>,
    pub(super) smtp_starttls: Option<bool>,
    pub(super) smtp_from: Option<String>,
    /// the admin of SAK_ADMIN_EMAIL
    pub(super) admin_email: Option<String>,
    /// the user mode of SAK_USER_MODE
    pub(super) user_mode: Option<UserMode>,
}

/// the variables of the environment a deployment is proposed from (`defaults`)
pub const NAME_VARIABLE: &str = "SAK_NAME";
pub const PASSWORD_VARIABLE: &str = "SAK_PASSWORD";
pub const LOCATION_VARIABLE: &str = "SAK_LOCATION";

pub const SMTP_HOST_VARIABLE: &str = "SAK_SMTP_HOST";
pub const SMTP_PORT_VARIABLE: &str = "SAK_SMTP_PORT";
pub const SMTP_USERNAME_VARIABLE: &str = "SAK_SMTP_USERNAME";
pub const SMTP_PASSWORD_VARIABLE: &str = "SAK_SMTP_PASSWORD";
pub const SMTP_STARTTLS_VARIABLE: &str = "SAK_SMTP_STARTTLS";
pub const SMTP_FROM_VARIABLE: &str = "SAK_SMTP_FROM";
pub const ADMIN_EMAIL_VARIABLE: &str = "SAK_ADMIN_EMAIL";
pub const USER_MODE_VARIABLE: &str = "SAK_USER_MODE";

pub use sak_cloud::hetzner::deployment::{AcmeCa, UserMode};

/// the acme directory caddy gets the certificates of `ca` from, none for its default issuers
pub fn acme_directory(ca: AcmeCa) -> Option<&'static str> {
    match ca {
        AcmeCa::Production => None,
        AcmeCa::Staging => Some("https://acme-staging-v02.api.letsencrypt.org/directory"),
    }
}

/// the roots sakctl trusts when it talks to a deployment with certificates of `ca`
pub fn acme_trust(ca: AcmeCa) -> crate::core::config::Trust {
    match ca {
        AcmeCa::Production => crate::core::config::Trust::Public,
        AcmeCa::Staging => crate::core::config::Trust::LetsEncryptStaging,
    }
}

/// the longest host name and e-mail address that dns and smtp allow; the page has the same limits
pub const MAX_HOST_LEN: usize = 253;
pub const MAX_ADDRESS_LEN: usize = 254;

/// the defaults of the backend
pub const DEFAULT_SMTP_PORT: u16 = 587;
pub const DEFAULT_SMTP_STARTTLS: bool = true;

/// the mail server the backend sends its login links with. No Debug: it holds a password;
/// Serialize only for the encrypted settings of `local`
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Smtp {
    pub host: String,
    pub port: u16,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub password: String,
    #[serde(default = "default_starttls")]
    pub starttls: bool,
    /// the sender of the mails, empty for noreply@<root domain> (`sender`); none in the settings
    /// an older sakctl kept
    #[serde(default)]
    pub from: String,
}

fn default_starttls() -> bool {
    DEFAULT_SMTP_STARTTLS
}

impl Smtp {
    pub fn sender(&self, root_domain: &str) -> String {
        match self.from.trim() {
            "" => default_sender(root_domain),
            from => from.to_string(),
        }
    }
}

pub fn default_sender(root_domain: &str) -> String {
    format!("noreply@{}", root_domain.trim())
}

/// no line break in a value: `backend.env` has one line for each value
pub(crate) fn check_smtp(smtp: &Smtp) -> Result<(), String> {
    let host = smtp.host.trim();
    if host.is_empty() || host.len() > MAX_HOST_LEN || host.contains(char::is_whitespace) {
        return Err(t!(
            "common.deployments.invalid_smtp_host",
            hint = crate::core::i18n::hint(crate::core::i18n::Hint::SmtpHost)
        )
        .to_string());
    }
    if smtp.port == 0 {
        return Err(t!("common.deployments.invalid_smtp_port").to_string());
    }
    if [smtp.username.as_str(), smtp.password.as_str()]
        .iter()
        .any(|value| value.contains(['\n', '\r']))
    {
        return Err(t!("common.deployments.invalid_smtp_value").to_string());
    }
    let from = smtp.from.trim();
    if !from.is_empty() && !is_address(from) {
        return Err(t!(
            "common.deployments.invalid_smtp_from",
            from = from,
            hint = crate::core::i18n::hint(crate::core::i18n::Hint::SmtpFrom)
        )
        .to_string());
    }
    Ok(())
}

/// whether `address` is an e-mail address as a deployment needs it; nothing that breaks a line of
/// `backend.env` or of yaml
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

/// the type the server `has`, so a deploy does not recreate a server resized in the console
pub(super) fn server_type_of(has: Option<ServerType>, location: Location) -> ServerType {
    has.filter(|server_type| *server_type != ServerType::unknown)
        .unwrap_or_else(|| server_type_for(location))
}

/// cx23 where hetzner offers it (the european locations), else cpx21
pub(super) fn server_type_for(location: Location) -> ServerType {
    match location {
        Location::fsn1 | Location::nbg1 | Location::hel1 => ServerType::cx23,
        Location::ash | Location::hil | Location::sin => ServerType::cpx21,
        // nothing is created there (`prepare`); the type only completes the declaration
        Location::unknown => ServerType::cx23,
    }
}

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
        smtp_from: variable(SMTP_FROM_VARIABLE),
        admin_email: variable(ADMIN_EMAIL_VARIABLE),
        user_mode: variable(USER_MODE_VARIABLE).and_then(|value| UserMode::parse(&value)),
    }
}

pub fn locations() -> Vec<LocationResponse> {
    LOCATIONS.to_vec()
}

pub(super) fn variable(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

/// every key and label of a deployment is derived from the password
pub(super) const MIN_PASSWORD_CHARS: usize = 16;

/// what a plan or an apply is for: `Create` a new deployment, `Deploy` an existing one to what it
/// declares (its server replaced when necessary)
#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Intent {
    Create,
    Deploy,
    /// remove every resource of the deployment, the data volume only with `delete_data`
    Destroy,
}

/// a deployment to plan and apply, as the page sends it or the command line builds it
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeploymentRequest {
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
    /// a destroy also deletes the data volume
    #[serde(default)]
    pub delete_data: bool,
    /// needed by a create and a deploy
    #[serde(default)]
    pub user_mode: Option<UserMode>,
    /// where the certificates come from. None: the ca of the deployment for a deploy, production
    /// for a create
    #[serde(default)]
    pub acme_ca: Option<AcmeCa>,
    /// the mail server of the e-mail login: needed for multi user, not used for single user
    #[serde(default)]
    pub smtp: Option<Smtp>,
    /// the first user of the e-mail login: optional for multi user, refused for single user
    #[serde(default)]
    pub admin_email: Option<String>,
    /// run the playbook even when the provisioning is up to date
    #[serde(default)]
    pub force: bool,
}

/// a name trimmed: 1 to MAX_NAME_LEN lower case letters, digits and dashes, so hetzner and dns
/// take it with the prefix and suffix of the cloud names. The only check of a name; a wrong name is
/// a usage error (`EXIT_USAGE`)
pub fn checked_name(name: &str) -> Result<&str, SakctlError> {
    let name = name.trim();
    check_name(name).map_err(|_| {
        SakctlError::new(
            t!("common.deployments.invalid_name", max = MAX_NAME_LEN),
            EXIT_USAGE,
        )
    })?;
    Ok(name)
}

impl DeploymentRequest {
    /// a deploy of `name` as it is: its settings come from the deployment
    /// (`DeploymentManager::completed`)
    pub fn as_it_is(name: &str, password: &str, location: Location) -> Self {
        DeploymentRequest {
            name: name.to_string(),
            password: password.to_string(),
            password_repeat: password.to_string(),
            location,
            dns_mode: None,
            root_domain: None,
            intent: Intent::Deploy,
            delete_data: false,
            user_mode: None,
            acme_ca: None,
            smtp: None,
            admin_email: None,
            force: false,
        }
    }
}
