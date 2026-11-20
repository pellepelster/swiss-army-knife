//! the test e-mail of a mail server before a deploy, sent from this machine as the backend will
//! send its login links: from its sender (noreply@<root domain> unless another is given), with
//! starttls or else implicit tls (never plain text, as `provision` configures the backend), logged
//! in when there is a user

use crate::core::deployments::{Smtp, check_smtp, is_address};
use lettre::message::Mailbox;
use lettre::message::header::ContentType;
use lettre::transport::smtp::authentication::Credentials;
use lettre::transport::smtp::client::{Tls, TlsParameters};
use lettre::{AsyncSmtpTransport, AsyncTransport, Message, Tokio1Executor};
use rust_i18n::t;
use std::time::Duration;

/// how long the mail server has for each answer
pub const TIMEOUT: Duration = Duration::from_secs(10);

/// what the test e-mail is about: the deployment `name` that will send with the mail server, the
/// `root_domain` of its default sender, and its recipient
pub struct TestMail<'a> {
    pub name: &'a str,
    pub root_domain: &'a str,
    pub to: &'a str,
}

/// sends the test e-mail with the mail server: what the user reads when the mail server took it,
/// the reason when it did not
pub async fn send_test(smtp: &Smtp, mail: &TestMail<'_>) -> Result<String, String> {
    check_smtp(smtp)?;
    let to = mail.to.trim();
    if !is_address(to) {
        return Err(t!("common.smtp.invalid_to", to = to).to_string());
    }
    if smtp.from.trim().is_empty() && mail.root_domain.trim().is_empty() {
        return Err(t!("common.smtp.no_root_domain").to_string());
    }
    let from: Mailbox = smtp
        .sender(mail.root_domain)
        .parse()
        .map_err(|_| t!("common.smtp.no_root_domain").to_string())?;
    let host = smtp.host.trim();
    let message = Message::builder()
        .from(from)
        .to(to.parse().map_err(|_| t!("common.smtp.invalid_to", to = to).to_string())?)
        .subject(t!("common.smtp.test_subject", name = mail.name).to_string())
        .header(ContentType::TEXT_PLAIN)
        .body(t!("common.smtp.test_body", name = mail.name, host = host).to_string())
        .map_err(|error| error.to_string())?;
    let parameters = TlsParameters::new(host.to_string()).map_err(|error| error.to_string())?;
    let tls = if smtp.starttls {
        Tls::Required(parameters)
    } else {
        Tls::Wrapper(parameters)
    };
    let mut builder = AsyncSmtpTransport::<Tokio1Executor>::builder_dangerous(host)
        .port(smtp.port)
        .tls(tls)
        .timeout(Some(TIMEOUT));
    if !smtp.username.is_empty() {
        builder = builder.credentials(Credentials::new(smtp.username.clone(), smtp.password.clone()));
    }
    let transport: AsyncSmtpTransport<Tokio1Executor> = builder.build();
    match transport.send(message).await {
        Ok(_) => Ok(t!("common.smtp.sent", host = host, to = to).to_string()),
        Err(error) => Err(reason(smtp, &error)),
    }
}

/// why the test e-mail was not sent. The two tls modes mixed up are told apart: implicit tls on a
/// starttls port reads plain text where it expects tls, starttls on an implicit tls port waits for
/// a greeting the server does not send before tls
fn reason(smtp: &Smtp, error: &lettre::transport::smtp::Error) -> String {
    let text = error.to_string();
    let key = if error.is_timeout() && smtp.starttls {
        "common.smtp.timeout_starttls"
    } else if error.is_timeout() {
        "common.smtp.timeout"
    } else if smtp.starttls && error.is_response() {
        // tls bytes where starttls expects the greeting in plain text
        "common.smtp.tls_first"
    } else if !smtp.starttls && text.contains("InvalidContentType") {
        "common.smtp.no_tls"
    } else if error.is_tls() {
        "common.smtp.tls"
    } else if error.is_permanent() || error.is_transient() {
        // an answer of the mail server, e.g. a login it refused
        "common.smtp.refused"
    } else if error.is_client() {
        // what the mail server offers does not fit, e.g. no starttls
        "common.smtp.failed"
    } else {
        "common.smtp.unreachable"
    };
    t!(
        key,
        host = smtp.host.trim(),
        port = smtp.port,
        error = text,
        seconds = TIMEOUT.as_secs()
    )
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
    use tokio::net::TcpListener;

    const MAIL: TestMail<'static> = TestMail {
        name: "example1",
        root_domain: "example.org",
        to: "admin@example.org",
    };

    fn smtp(port: u16, starttls: bool) -> Smtp {
        Smtp {
            host: "127.0.0.1".to_string(),
            port,
            username: "user".to_string(),
            password: "secret".to_string(),
            starttls,
            from: String::new(),
        }
    }

    /// a mail server that answers its greeting and ehlo, but offers no starttls
    async fn server_without_starttls() -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let (read, mut write) = stream.into_split();
            let mut lines = BufReader::new(read).lines();
            write.write_all(b"220 test ESMTP\r\n").await.unwrap();
            while let Ok(Some(line)) = lines.next_line().await {
                let answer: &[u8] = if line.starts_with("EHLO") {
                    b"250-test\r\n250 AUTH PLAIN\r\n"
                } else if line.starts_with("QUIT") {
                    b"221 bye\r\n"
                } else {
                    b"502 not implemented\r\n"
                };
                if write.write_all(answer).await.is_err() {
                    break;
                }
            }
        });
        port
    }

    /// the text of `key` up to its error, which comes last
    fn prefix(key: &str) -> String {
        t!(key, host = "127.0.0.1", error = "", seconds = TIMEOUT.as_secs()).to_string()
    }

    #[tokio::test]
    async fn a_server_without_starttls_gets_no_mail() {
        let port = server_without_starttls().await;
        let error = send_test(&smtp(port, true), &MAIL).await.unwrap_err();
        assert!(error.starts_with(&prefix("common.smtp.failed")), "{error}");
        assert!(error.contains("STARTTLS"), "{error}");
        assert!(!error.contains("secret"), "{error}");
    }

    #[tokio::test]
    async fn a_closed_port_is_unreachable() {
        let port = {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            listener.local_addr().unwrap().port()
        };
        let error = send_test(&smtp(port, false), &MAIL).await.unwrap_err();
        assert!(error.starts_with(&prefix("common.smtp.unreachable")), "{error}");
    }

    #[tokio::test]
    async fn the_recipient_and_the_root_domain_are_checked_before_the_mail_server_is_asked() {
        let error = send_test(&smtp(1, true), &TestMail { to: "nobody", ..MAIL }).await.unwrap_err();
        assert_eq!(error, t!("common.smtp.invalid_to", to = "nobody"));
        let error = send_test(&smtp(1, true), &TestMail { root_domain: "", ..MAIL }).await.unwrap_err();
        assert_eq!(error, t!("common.smtp.no_root_domain"));
    }

    #[tokio::test]
    async fn the_fields_are_checked_first() {
        let error = send_test(
            &Smtp {
                host: "smtp example.org".to_string(),
                ..smtp(587, true)
            },
            &MAIL,
        )
        .await
        .unwrap_err();
        assert_eq!(
            error,
            t!(
                "common.deployments.invalid_smtp_host",
                hint = crate::core::i18n::hint(crate::core::i18n::Hint::SmtpHost)
            )
        );
    }
}
