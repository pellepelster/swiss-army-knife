//! the settings a deploy takes from the existing deployment when they are not given: from the
//! running deployment (`GET /v1/deployment`), else from what this machine kept of its last
//! create or deploy (`~/.sak/<name>/config.json`), else from its dns records, which tell the root
//! domain and the dns mode only. The resources carry no settings as labels

use super::*;

/// where the settings of a deployment came from, for its page and the config form
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum SettingsSource {
    /// the running deployment answered them
    Running,
    /// the settings this machine kept of the last create or deploy
    Local,
    /// the dns records of the deployment: its root domain and dns mode only
    Records,
}

/// what a create or deploy keeps of a deployment on this machine (`local`), encrypted for its
/// password: what the config form takes when the deployment does not answer. It holds the password of
/// the mail server, so no Debug.
///
/// The file may come from another version of sakctl, older or newer, so every field is read on its
/// own: a field it lacks or a value this version does not know (a new dns mode) is none, the
/// others still count; a field this version does not know is ignored
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalSettings {
    #[serde(default, deserialize_with = "lenient")]
    pub location: Option<Location>,
    #[serde(default, deserialize_with = "lenient")]
    pub dns_mode: Option<DnsMode>,
    #[serde(default, deserialize_with = "lenient")]
    pub root_domain: Option<String>,
    #[serde(default, deserialize_with = "lenient")]
    pub user_mode: Option<UserMode>,
    #[serde(default, deserialize_with = "lenient")]
    pub admin_email: Option<String>,
    #[serde(default, deserialize_with = "lenient")]
    pub smtp: Option<Smtp>,
    #[serde(default, deserialize_with = "lenient")]
    pub acme_ca: Option<AcmeCa>,
    /// the version of sakctl that wrote them
    #[serde(default, deserialize_with = "lenient")]
    pub version: Option<String>,
    /// when the create or deploy that kept them started, rfc 3339
    #[serde(default, deserialize_with = "lenient")]
    pub deployed_at: Option<String>,
}

/// a field of `LocalSettings` as far as this version can read it: none when it cannot
fn lenient<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: serde::de::DeserializeOwned,
{
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(serde_json::from_value(value).ok())
}

/// the settings a deploy takes from the existing deployment when they are not given
/// (`DeploymentManager::detected`)
#[derive(Clone, Debug, Default, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Detected {
    /// where they came from; none when nothing was found
    pub source: Option<SettingsSource>,
    /// when the create or deploy that kept them started, for `SettingsSource::Local`
    pub deployed_at: Option<String>,
    pub dns_mode: Option<DnsMode>,
    pub root_domain: Option<String>,
    pub acme_ca: Option<AcmeCa>,
    pub user_mode: Option<UserMode>,
    pub admin_email: Option<String>,
    pub smtp: Option<DetectedSmtp>,
    /// why the running deployment could not be read: the backend did not answer, its
    /// certificate was refused, …; none when it was read
    pub unread: Option<String>,
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
    /// the sender of the mails, as the deployment has it
    pub from: String,
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
            .field("from", &self.from)
            .finish()
    }
}

impl Detected {
    /// the settings the running deployment answered
    pub(super) fn from_running(running: crate::core::config::DeploymentSettings) -> Self {
        let user_mode = UserMode::parse(&running.user_mode);
        let multi = user_mode == Some(UserMode::Multi);
        Detected {
            source: Some(SettingsSource::Running),
            dns_mode: DnsMode::parse(&running.dns_mode),
            root_domain: Some(running.root_domain),
            acme_ca: Some(acme_ca_of(running.acme_ca.as_deref())),
            user_mode,
            admin_email: running.admin_email.filter(|_| multi),
            smtp: running.smtp.filter(|_| multi).map(|smtp| DetectedSmtp {
                host: smtp.host,
                port: smtp.port,
                username: smtp.username.unwrap_or_default(),
                password: smtp.password,
                password_set: smtp.password_set,
                starttls: smtp.starttls,
                from: smtp.from,
            }),
            ..Detected::default()
        }
    }

    /// the settings this machine kept of the last create or deploy
    pub(super) fn from_local(local: LocalSettings) -> Self {
        let multi = local.user_mode == Some(UserMode::Multi);
        Detected {
            source: Some(SettingsSource::Local),
            deployed_at: local.deployed_at,
            dns_mode: local.dns_mode,
            root_domain: local.root_domain.filter(|domain| !domain.trim().is_empty()),
            acme_ca: local.acme_ca,
            user_mode: local.user_mode,
            admin_email: local.admin_email.filter(|_| multi),
            smtp: local.smtp.filter(|_| multi).map(|smtp| DetectedSmtp {
                password_set: !smtp.password.is_empty(),
                password: Some(smtp.password),
                host: smtp.host,
                port: smtp.port,
                username: smtp.username,
                starttls: smtp.starttls,
                from: smtp.from,
            }),
            ..Detected::default()
        }
    }

    /// what the dns records of the deployment tell: its root domain and dns mode; nothing without
    /// records
    pub(super) fn from_records(deployment: &DiscoveredDeployment) -> Self {
        let Some(root_domain) = deployment.root_domain() else {
            return Detected::default();
        };
        Detected {
            source: Some(SettingsSource::Records),
            dns_mode: deployment.dns_mode(),
            root_domain: Some(root_domain),
            ..Detected::default()
        }
    }
}

/// the acme ca of an acme directory as the backend has it: none is the production ca
fn acme_ca_of(directory: Option<&str>) -> AcmeCa {
    match directory {
        Some(directory) if acme_directory(AcmeCa::Staging) == Some(directory) => AcmeCa::Staging,
        _ => AcmeCa::Production,
    }
}

/// a deploy completed from `detected`; when it still lacks what only the running configuration
/// tells (the user mode, or the admin and mail server of multi user) and that could not be read,
/// the reason it could not is the error, not a missing value later
pub(super) fn completed_from(
    deployment: DeploymentRequest,
    detected: &Detected,
    smtp_password: &str,
) -> Result<DeploymentRequest, SakctlError> {
    let deployment = complete(deployment, detected, smtp_password)?;
    let lacking = match deployment.user_mode {
        None => true,
        Some(UserMode::Multi) => deployment.smtp.is_none() || deployment.admin_email.is_none(),
        Some(UserMode::Single) => false,
    };
    match &detected.unread {
        Some(error) if lacking => Err(t!(
            "common.deployments.settings_unread",
            name = deployment.name,
            error = error
        )
        .to_string()
        .into()),
        _ => Ok(deployment),
    }
}

/// a deploy completed from `detected` where a value is not given: the dns mode, the root domain,
/// the acme ca, the user mode and, for multi user, the admin and the mail server. A given `smtp_password` wins over the detected one. An
/// older backend does not answer the password; then the password must be given, because a deploy
/// without it removes it
pub(super) fn complete(
    mut deployment: DeploymentRequest,
    detected: &Detected,
    smtp_password: &str,
) -> Result<DeploymentRequest, SakctlError> {
    if deployment.dns_mode.is_none() {
        deployment.dns_mode = detected.dns_mode;
    }
    if deployment.root_domain.is_none() {
        deployment.root_domain = detected.root_domain.clone();
    }
    if deployment.acme_ca.is_none() {
        deployment.acme_ca = detected.acme_ca;
    }
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
                return Err(SakctlError::new(
                    t!(
                        "common.deployments.smtp_password_needed",
                        hint = crate::core::i18n::hint(crate::core::i18n::Hint::SmtpPassword)
                    ),
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
            from: smtp.from.clone(),
        });
    }
    Ok(deployment)
}
