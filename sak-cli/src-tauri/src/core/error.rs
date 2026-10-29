//! what failed, as the window shows it and the command line ends with: a message and the exit
//! code of the process. Failures a script must tell apart (a root domain that cannot be used, a
//! resource of someone else, a refused recreate) have a code of their own

use serde::{Serialize, Serializer};
use std::fmt;

/// the command did what it should
pub const EXIT_OK: i32 = 0;
/// anything else failed: a check, a password, the provider, the provisioning
pub const EXIT_FAILED: i32 = 1;
/// the arguments are wrong: what the argument parser refuses, a name that is not valid, a
/// password or token missing without a terminal
pub const EXIT_USAGE: i32 = 2;
/// domain mode asked, but another deployment manages the root domain in domain mode already
pub const EXIT_DOMAIN_TAKEN: i32 = 3;
/// the dns mode asked is not the one the other deployments of the root domain use
pub const EXIT_MIXED_DNS_MODES: i32 = 4;
/// a resource with a name the deployment declares exists and belongs to someone else (it does
/// not have the name label or the password hash of the deployment); nothing of it was changed
pub const EXIT_NOT_OWNED: i32 = 5;
/// a resource that must stay (the data volume, a primary ip) would have to be created anew and
/// lose what it holds; nothing was changed
pub const EXIT_RECREATE_REFUSED: i32 = 6;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WizardError {
    pub message: String,
    pub exit_code: i32,
}

impl WizardError {
    pub fn new(message: impl ToString, exit_code: i32) -> Self {
        WizardError {
            message: message.to_string(),
            exit_code,
        }
    }
}

impl From<String> for WizardError {
    fn from(message: String) -> Self {
        WizardError::new(message, EXIT_FAILED)
    }
}

impl fmt::Display for WizardError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

/// the page gets only the message
impl Serialize for WizardError {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.message)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_error_is_its_message_to_the_page_and_fails_with_one_unless_told() {
        let error = WizardError::from("no".to_string());
        assert_eq!(error.exit_code, EXIT_FAILED);
        assert_eq!(
            serde_json::to_value(&error).unwrap(),
            serde_json::json!("no")
        );
        assert_eq!(
            WizardError::new("taken", EXIT_DOMAIN_TAKEN).to_string(),
            "taken"
        );
    }
}
