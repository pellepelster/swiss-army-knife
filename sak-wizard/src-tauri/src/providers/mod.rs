//! the providers sak-wizard creates resources at, each with the api token it is used with

pub mod hetzner;

use rust_i18n::t;
use serde::{Deserialize, Serialize};
use std::fmt;
use std::sync::Mutex;
use tauri::State;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ProviderType {
    Hetzner,
}

impl ProviderType {
    pub const ALL: &[ProviderType] = &[ProviderType::Hetzner];

    // the product names, they are not translated
    pub fn name(self) -> &'static str {
        match self {
            ProviderType::Hetzner => "Hetzner Cloud",
        }
    }

    // below ui/
    fn logo(self) -> &'static str {
        match self {
            ProviderType::Hetzner => "providers/hetzner.svg",
        }
    }

    fn token_hint(self) -> String {
        match self {
            ProviderType::Hetzner => t!("providers.hetzner.token_hint").to_string(),
        }
    }

    /// the environment variable the token is taken from on startup, the one of the provider's own tools
    pub fn token_variable(self) -> &'static str {
        match self {
            ProviderType::Hetzner => "HCLOUD_TOKEN",
        }
    }

    /// a token is only valid when it can create resources, checked by creating and deleting one
    pub async fn validate(self, token: &str) -> Result<(), String> {
        match self {
            ProviderType::Hetzner => hetzner::HetznerCloud::new().validate(token).await,
        }
    }

    pub(crate) fn response(self) -> ProviderTypeResponse {
        ProviderTypeResponse {
            id: self,
            name: self.name(),
            logo: self.logo(),
            token_hint: self.token_hint(),
        }
    }
}

struct ProviderToken {
    provider: ProviderType,
    token: String,
}

// the token never shows up in a log or a panic message
impl fmt::Debug for ProviderToken {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ProviderToken")
            .field("provider", &self.provider)
            .field("token", &mask(&self.token))
            .finish()
    }
}

/// the valid tokens, held in memory as long as sak-wizard runs; they never go back to the ui,
/// only masked
#[derive(Debug, Default)]
pub struct ProviderTokens(Mutex<Vec<ProviderToken>>);

impl ProviderTokens {
    pub fn add(
        &self,
        provider: ProviderType,
        token: &str,
    ) -> Result<ProviderTokenResponse, String> {
        let mut tokens = self.0.lock().map_err(|e| e.to_string())?;
        if tokens
            .iter()
            .any(|known| known.provider == provider && known.token == token)
        {
            return Err(t!("providers.duplicate").to_string());
        }
        let token = ProviderToken {
            provider,
            token: token.to_string(),
        };
        let response = ProviderTokenResponse::of(&token);
        tokens.push(token);
        Ok(response)
    }

    pub fn list(&self) -> Vec<ProviderTokenResponse> {
        self.0
            .lock()
            .map(|tokens| tokens.iter().map(ProviderTokenResponse::of).collect())
            .unwrap_or_default()
    }

    /// the tokens themselves, for the calls to the providers
    pub fn tokens(&self) -> Vec<(ProviderType, String)> {
        self.0
            .lock()
            .map(|tokens| {
                tokens
                    .iter()
                    .map(|token| (token.provider, token.token.clone()))
                    .collect()
            })
            .unwrap_or_default()
    }

    pub fn is_empty(&self) -> bool {
        self.0
            .lock()
            .map(|tokens| tokens.is_empty())
            .unwrap_or(true)
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProviderTypeResponse {
    id: ProviderType,
    name: &'static str,
    logo: &'static str,
    token_hint: String,
}

#[derive(Debug, Serialize)]
pub struct ProviderTokenResponse {
    provider: ProviderTypeResponse,
    // masked, the first characters only
    token: String,
}

impl ProviderTokenResponse {
    fn of(token: &ProviderToken) -> Self {
        Self {
            provider: token.provider.response(),
            token: mask(&token.token),
        }
    }
}

// the first 4 characters and a fixed number of stars, the length of the token stays hidden;
// a token too short to keep most of it hidden is shown as stars only
fn mask(token: &str) -> String {
    const SHOWN: usize = 4;
    if token.chars().count() <= 2 * SHOWN {
        return "****".to_string();
    }
    format!("{}****", token.chars().take(SHOWN).collect::<String>())
}

#[tauri::command]
pub fn list_provider_types() -> Vec<ProviderTypeResponse> {
    ProviderType::ALL
        .iter()
        .map(|provider| provider.response())
        .collect()
}

#[tauri::command]
pub fn list_provider_tokens(tokens: State<'_, ProviderTokens>) -> Vec<ProviderTokenResponse> {
    tokens.list()
}

#[tauri::command]
pub async fn add_provider_token(
    tokens: State<'_, ProviderTokens>,
    provider: ProviderType,
    token: String,
) -> Result<ProviderTokenResponse, String> {
    let token = token.trim();
    if token.is_empty() {
        return Err(t!("providers.empty_token").to_string());
    }
    provider.validate(token).await?;
    tokens.add(provider, token)
}

#[cfg(test)]
mod tests {
    use super::*;

    const TOKEN: &str = "abcdefghijklmnopqrstuvwxyz";

    #[test]
    fn a_token_shows_its_first_4_characters_only() {
        assert_eq!(mask(TOKEN), "abcd****");
    }

    #[test]
    fn a_short_token_shows_nothing() {
        assert_eq!(mask("abcdefgh"), "****");
    }

    #[test]
    fn the_tokens_are_listed_masked() {
        let tokens = ProviderTokens::default();
        tokens.add(ProviderType::Hetzner, TOKEN).unwrap();
        let list = serde_json::to_string(&tokens.list()).unwrap();
        assert!(list.contains("\"token\":\"abcd****\""), "{list}");
        assert!(list.contains("\"name\":\"Hetzner Cloud\""), "{list}");
        assert!(!list.contains(TOKEN), "{list}");
        assert!(!format!("{tokens:?}").contains(TOKEN));
    }

    #[test]
    fn a_token_is_added_once() {
        let tokens = ProviderTokens::default();
        tokens.add(ProviderType::Hetzner, TOKEN).unwrap();
        assert_eq!(
            tokens.add(ProviderType::Hetzner, TOKEN).unwrap_err(),
            "This token was added already."
        );
    }
}
