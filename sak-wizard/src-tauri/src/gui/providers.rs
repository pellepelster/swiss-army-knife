//! the commands of the pages about the providers and their tokens

use crate::core::providers::{
    ProviderTokenResponse, ProviderTokens, ProviderType, ProviderTypeResponse,
};
use rust_i18n::t;
use tauri::State;

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
