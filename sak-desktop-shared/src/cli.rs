//! what the command lines of the desktop apps share: when the arguments ask for the command line,
//! where the app keeps its files, the session a command runs with: the one of two variables
//! (scripts and tests), else the sign-in of the window kept in the keychain for the address used
//! last (DESKTOP-1), and the texts of what failed

use crate::api::ApiError;
use crate::instances::{FILE_NAME, InstanceStore};
use crate::secrets::Keychain;
use crate::session::{Address, LOGIN_TIMEOUT, RestoreError, SessionManager};
use std::path::PathBuf;
use std::sync::Arc;

/// whether the arguments ask for the command line: a word (a command, a mistyped one too, which
/// clap answers with its usage error), help or the version as the first argument. Nothing, or
/// another flag (what a desktop passes, e.g. `-psn_…` on macOS), opens the window
pub fn is_command_line(args: &[String]) -> bool {
    match args.first().map(String::as_str) {
        Some("--help" | "-h" | "--version" | "-V") => true,
        Some(first) => !first.starts_with('-'),
        None => false,
    }
}

/// the config directory of the app of `identifier`, as tauri has it (`app_config_dir`), for a
/// command line that runs without tauri
pub fn config_dir(identifier: &str) -> PathBuf {
    let home = std::env::home_dir().unwrap_or_default();
    let base = if cfg!(target_os = "macos") {
        home.join("Library/Application Support")
    } else if cfg!(windows) {
        std::env::var_os("APPDATA")
            .map(PathBuf::from)
            .unwrap_or(home)
    } else {
        std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .filter(|path| path.is_absolute())
            .unwrap_or_else(|| home.join(".config"))
    };
    base.join(identifier)
}

/// why a command has no session; the app says it in its own words
#[derive(Debug)]
pub enum SessionError {
    /// the address of the variable is none
    InvalidAddress(String),
    /// neither the variables nor a sign-in of the window
    NotSignedIn,
    Restore(RestoreError),
}

/// the session of a command of the app of `identifier`: the address and the token of the two
/// variables when both are set, else the sign-in the window kept for the address it used last
pub async fn session(
    identifier: &str,
    address_variable: &str,
    token_variable: &str,
) -> Result<SessionManager, SessionError> {
    if let (Ok(address), Ok(token)) = (
        std::env::var(address_variable),
        std::env::var(token_variable),
    ) {
        let parsed =
            Address::parse(&address).map_err(|_| SessionError::InvalidAddress(address.clone()))?;
        let sessions = SessionManager::default();
        sessions
            .use_token(parsed, token)
            .await
            .map_err(SessionError::Restore)?;
        return Ok(sessions);
    }
    let instances = InstanceStore::new(config_dir(identifier).join(FILE_NAME));
    let address = instances.last().ok_or(SessionError::NotSignedIn)?;
    let sessions = SessionManager::new(Arc::new(Keychain::new(identifier)), LOGIN_TIMEOUT);
    match sessions
        .restore(address.clone(), instances.api(&address))
        .await
    {
        Ok(Some(_)) => Ok(sessions),
        Ok(None) => Err(SessionError::NotSignedIn),
        Err(error) => Err(SessionError::Restore(error)),
    }
}

/// the text of an error of the api: its messages, or what failed; `not_signed_in` says in the words
/// of the app how to sign in
pub fn api_message(error: &ApiError, not_signed_in: &str) -> String {
    match error {
        ApiError::SignedOut => not_signed_in.to_string(),
        ApiError::Refused { status, errors } if errors.is_empty() => {
            rust_i18n::t!("cli.refused", status = status).to_string()
        }
        ApiError::Refused { errors, .. } => errors
            .iter()
            .map(|error| error.message.as_str())
            .collect::<Vec<_>>()
            .join(" "),
        ApiError::Failed { message } => failed_message(message),
    }
}

/// the text of something that failed with `error`
pub fn failed_message(error: impl std::fmt::Display) -> String {
    rust_i18n::t!("cli.failed", error = error.to_string()).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(line: &[&str]) -> Vec<String> {
        line.iter().map(|arg| arg.to_string()).collect()
    }

    #[test]
    fn a_word_help_or_the_version_is_the_command_line_nothing_or_another_flag_the_window() {
        for command_line in [&["run"][..], &["--help"], &["-V"], &["typo", "--x"]] {
            assert!(is_command_line(&args(command_line)), "{command_line:?}");
        }
        for window in [&[][..], &["-psn_0_12345"], &["--some-flag"]] {
            assert!(!is_command_line(&args(window)), "{window:?}");
        }
    }

    #[test]
    fn an_error_of_the_api_says_its_messages_else_its_status_or_what_failed() {
        assert_eq!(api_message(&ApiError::SignedOut, "sign in"), "sign in");
        assert_eq!(
            api_message(
                &ApiError::Refused {
                    status: 503,
                    errors: vec![]
                },
                ""
            ),
            "The SAK instance refused it (503)."
        );
        assert_eq!(
            api_message(
                &ApiError::Failed {
                    message: "connection refused".to_string()
                },
                ""
            ),
            "It failed: connection refused"
        );
    }

    #[test]
    fn the_config_directory_is_named_by_the_identifier() {
        assert!(config_dir("io.pelle.sak.backup").ends_with("io.pelle.sak.backup"));
    }
}
