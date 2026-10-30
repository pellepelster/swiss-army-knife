//! the settings a deploy takes from the existing deployment when they are not given

use super::*;

/// the settings a deploy takes from the existing deployment when they are not given
/// (`DeploymentManager::detected`)
#[derive(Clone, Debug, Default, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Detected {
    pub user_mode: Option<UserMode>,
    pub admin_email: Option<String>,
    pub smtp: Option<DetectedSmtp>,
    /// why the running configuration could not be read: the backend did not answer, its
    /// certificate was refused, …; none when it was read or not needed (single user)
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
pub(super) fn detect(
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
        unread: None,
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
            "shared.deployments.settings_unread",
            name = deployment.name,
            error = error
        )
        .to_string()
        .into()),
        _ => Ok(deployment),
    }
}

/// a deploy completed from `detected` where a value is not given: the user mode and, for multi
/// user, the admin and the mail server. A given `smtp_password` wins over the detected one. An
/// older backend does not answer the password; then the password must be given, because a deploy
/// without it removes it
pub(super) fn complete(
    mut deployment: DeploymentRequest,
    detected: &Detected,
    smtp_password: &str,
) -> Result<DeploymentRequest, SakctlError> {
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
                        "shared.deployments.smtp_password_needed",
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
        });
    }
    Ok(deployment)
}
