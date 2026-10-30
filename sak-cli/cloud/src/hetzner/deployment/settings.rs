//! the user mode and the acme ca of a deployment, each a label on its resources

use serde::{Deserialize, Serialize};
use std::fmt;

/// the user mode: who signs in to a deployment. `Single`: one user, the admin, with the password
/// of the deployment. `Multi`: users with a link by e-mail, the admin also with the password; it
/// needs a mail server and the address of the admin
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum UserMode {
    Single,
    Multi,
}

impl UserMode {
    pub const ALL: [UserMode; 2] = [UserMode::Single, UserMode::Multi];

    /// the value in the label and on the command line
    pub fn as_str(self) -> &'static str {
        match self {
            UserMode::Single => "single",
            UserMode::Multi => "multi",
        }
    }

    /// any case, blanks around it
    pub fn parse(value: &str) -> Option<UserMode> {
        UserMode::ALL
            .into_iter()
            .find(|mode| mode.as_str() == value.trim().to_lowercase())
    }
}

impl fmt::Display for UserMode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
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

    /// the value in the label and on the command line
    pub fn as_str(self) -> &'static str {
        match self {
            AcmeCa::Production => "production",
            AcmeCa::Staging => "staging",
        }
    }

    /// any case, blanks around it
    pub fn parse(value: &str) -> Option<AcmeCa> {
        AcmeCa::ALL
            .into_iter()
            .find(|ca| ca.as_str() == value.trim().to_lowercase())
    }
}

impl fmt::Display for AcmeCa {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn the_modes_parse_in_any_case_and_are_lower_case_in_json() {
        assert_eq!(UserMode::parse(" Multi "), Some(UserMode::Multi));
        assert_eq!(UserMode::parse("many"), None);
        assert_eq!(AcmeCa::parse("STAGING"), Some(AcmeCa::Staging));
        assert_eq!(AcmeCa::parse("test"), None);
        assert_eq!(
            serde_json::to_value(UserMode::Single).unwrap(),
            json!("single")
        );
        assert_eq!(
            serde_json::to_value(AcmeCa::Production).unwrap(),
            json!("production")
        );
    }
}
