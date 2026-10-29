use super::{Check, CheckResult, Found, Progress};
use crate::core::container::block_on;
use crate::core::providers::ProviderType;
use rust_i18n::t;

/// the provider tokens given to sakctl, each validated and given back. For the window, the tokens
/// of the variables that are set, e.g. HCLOUD_TOKEN, so the user does not enter them. For the
/// command line, its token from HCLOUD_TOKEN or the terminal. Each named by where it came from
pub struct ProviderTokensCheck {
    tokens: Vec<(ProviderType, String, &'static str)>,
}

impl ProviderTokensCheck {
    /// the tokens with where they came from, the empty ones left out
    pub fn new(tokens: Vec<(ProviderType, String, &'static str)>) -> Self {
        let tokens = tokens
            .into_iter()
            .map(|(provider, token, source)| (provider, token.trim().to_string(), source))
            .filter(|(_, token, _)| !token.is_empty())
            .collect();
        Self { tokens }
    }

    /// the token of each provider whose variable is set
    pub fn from_environment() -> Self {
        Self::new(
            ProviderType::ALL
                .iter()
                .filter_map(|provider| {
                    let variable = provider.token_variable();
                    std::env::var(variable)
                        .ok()
                        .map(|token| (*provider, token, variable))
                })
                .collect(),
        )
    }
}

impl Check for ProviderTokensCheck {
    fn id(&self) -> &'static str {
        "provider_tokens"
    }

    fn name(&self) -> String {
        t!("shared.checks.provider_tokens.name").to_string()
    }

    fn applies(&self) -> bool {
        !self.tokens.is_empty()
    }

    fn run(&self, found: &mut Found, _: &Progress) -> CheckResult {
        let mut sources = Vec::new();
        for (provider, token, source) in &self.tokens {
            block_on(provider.validate(token)).map_err(|error| {
                t!(
                    "shared.checks.provider_tokens.invalid",
                    variable = source,
                    provider = provider.name(),
                    error = error
                )
                .to_string()
            })?;
            found.tokens.push((*provider, token.clone()));
            sources.push(*source);
        }
        Ok(t!(
            "shared.checks.provider_tokens.passed",
            variables = sources.join(", ")
        )
        .to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_tokens_are_left_out_and_without_one_the_check_does_not_apply() {
        let check = ProviderTokensCheck::new(vec![(
            ProviderType::Hetzner,
            "  ".to_string(),
            "HCLOUD_TOKEN",
        )]);
        assert!(!check.applies());
        let check = ProviderTokensCheck::new(vec![(
            ProviderType::Hetzner,
            " t ".to_string(),
            "--hcloud-token",
        )]);
        assert!(check.applies());
        assert_eq!(check.tokens[0].1, "t");
    }
}
