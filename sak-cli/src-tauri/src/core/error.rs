//! what failed, as the window shows it and the command line ends with: a message and the
//! exit code of the process. A root domain that cannot be used has a code of its own, so a
//! script can tell it from any other failure

use serde::{Serialize, Serializer};
use std::fmt;

/// the command did what it should
pub const EXIT_OK: i32 = 0;
/// anything else failed: a check, a password, the provider, the provisioning
pub const EXIT_FAILED: i32 = 1;
/// the arguments are wrong (the code of the argument parser, named here for the help)
#[cfg_attr(not(test), allow(dead_code))]
pub const EXIT_USAGE: i32 = 2;
/// domain mode asked, another deployment manages the root domain in domain mode already
pub const EXIT_DOMAIN_TAKEN: i32 = 3;
/// the dns mode asked is not the one other deployments use the root domain in
pub const EXIT_MIXED_DNS_MODES: i32 = 4;
/// a resource of a name the deployment declares exists and belongs to someone else (it lacks
/// the sak name or the password hash of the deployment); nothing of it was changed
pub const EXIT_NOT_OWNED: i32 = 5;

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

/// the page gets the message alone, as every error before
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
