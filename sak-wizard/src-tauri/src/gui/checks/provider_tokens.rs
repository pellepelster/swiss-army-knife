use super::{Check, CheckResult, Progress};
use crate::core::providers::{ProviderTokens, ProviderType};
use rust_i18n::t;
use tauri::{AppHandle, Manager};

/// the token of each provider whose variable is set, e.g. HCLOUD_TOKEN, is validated and used,
/// the entry of the tokens is skipped then
pub struct ProviderTokensCheck;

fn token(provider: ProviderType) -> Option<String> {
    std::env::var(provider.token_variable())
        .ok()
        .map(|token| token.trim().to_string())
        .filter(|token| !token.is_empty())
}

impl Check for ProviderTokensCheck {
    fn id(&self) -> &'static str {
        "provider_tokens"
    }

    fn name(&self) -> String {
        t!("checks.provider_tokens.name").to_string()
    }

    fn applies(&self) -> bool {
        ProviderType::ALL
            .iter()
            .any(|provider| token(*provider).is_some())
    }

    fn run(&self, app: &AppHandle, _: &Progress) -> CheckResult {
        let tokens = app.state::<ProviderTokens>();
        let mut variables = Vec::new();
        for provider in ProviderType::ALL {
            let Some(token) = token(*provider) else {
                continue;
            };
            let variable = provider.token_variable();
            tauri::async_runtime::block_on(provider.validate(&token)).map_err(|error| {
                t!(
                    "checks.provider_tokens.invalid",
                    variable = variable,
                    provider = provider.name(),
                    error = error
                )
                .to_string()
            })?;
            tokens.add(*provider, &token)?;
            variables.push(variable);
        }
        Ok(t!(
            "checks.provider_tokens.passed",
            variables = variables.join(", ")
        )
        .to_string())
    }
}
