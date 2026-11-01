//! `sakctl mail test`: a test e-mail with the mail server of a deployment, or with one given only
//! by its flags before a create, the same one the window sends (`core::smtp`)

use super::*;
use crate::core::deployments::{Detected, DetectedSmtp};
use crate::core::smtp::{TestMail, send_test};

/// sends the test e-mail; a deployment gives its mail server, its sender and its root domain, the
/// flags change what they name
pub(super) async fn mail_test(args: MailTestArgs) -> Result<i32, SakctlError> {
    let smtp_password = smtp_password(args.smtp.smtp_password, smtp_password_variable())?;
    let detected = match &args.name {
        Some(name) => {
            let password = password(args.password, Typed::Once, password_variable())?;
            let token = hcloud_token(args.provider.hcloud_token, std::env::var("HCLOUD_TOKEN").ok())?;
            log(Level::Info, t!("cli.mail.reading", name = name));
            DeploymentManager
                .detected(vec![(ProviderType::Hetzner, token.0)], name, &password.0)
                .await?
        }
        None => Detected::default(),
    };
    let smtp = mail_server(&args, detected.smtp.as_ref(), &smtp_password)?;
    let root_domain = args
        .root_domain
        .clone()
        .or(detected.root_domain.clone())
        .unwrap_or_default();
    let name = args.name.clone().unwrap_or_else(|| match root_domain.as_str() {
        "" => smtp.host.trim().to_string(),
        domain => domain.to_string(),
    });
    log(Level::Info, t!("cli.mail.sending", to = args.to.trim(), from = smtp.sender(&root_domain)));
    let sent = send_test(
        &smtp,
        &TestMail {
            name: &name,
            root_domain: &root_domain,
            to: &args.to,
        },
    )
    .await?;
    log(Level::Info, sent);
    Ok(EXIT_OK)
}

/// the mail server of the test: the one of the deployment with what the flags change, or the one
/// of the flags alone when they name a host or there is no deployment. A password typed or of
/// SAK_SMTP_PASSWORD wins over the deployment's
pub(super) fn mail_server(
    args: &MailTestArgs,
    detected: Option<&DetectedSmtp>,
    smtp_password: &str,
) -> Result<Smtp, SakctlError> {
    let flags = &args.smtp;
    let base = match (&flags.smtp_host, detected) {
        (Some(host), _) => Smtp {
            host: host.clone(),
            port: DEFAULT_SMTP_PORT,
            username: String::new(),
            password: String::new(),
            starttls: DEFAULT_SMTP_STARTTLS,
            from: String::new(),
        },
        (None, Some(smtp)) => Smtp {
            host: smtp.host.clone(),
            port: smtp.port,
            username: smtp.username.clone(),
            password: smtp.password.clone().unwrap_or_default(),
            starttls: smtp.starttls,
            from: smtp.from.clone(),
        },
        (None, None) => {
            let key = match &args.name {
                Some(name) => t!("cli.mail.no_smtp", name = name),
                None => t!("cli.mail.no_host"),
            };
            return Err(SakctlError::new(key, EXIT_USAGE));
        }
    };
    Ok(Smtp {
        port: flags.smtp_port.unwrap_or(base.port),
        username: flags.smtp_username.clone().unwrap_or(base.username),
        password: if smtp_password.is_empty() {
            base.password
        } else {
            smtp_password.to_string()
        },
        starttls: flags.smtp_starttls.unwrap_or(base.starttls),
        from: flags.smtp_from.clone().unwrap_or(base.from),
        host: base.host,
    })
}
