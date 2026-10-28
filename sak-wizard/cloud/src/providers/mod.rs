//! the providers resources live at: a configuration names one and holds its credentials,
//! validated into a runtime that registers the provisioners of its resources

pub mod hetzner;

use crate::api::Registry;
use crate::api::error::Result;
use async_trait::async_trait;

/// how a provider is reached, as declared: its type, a name to tell several of one type
/// apart, and whatever it needs (a token, defaults)
pub trait ProviderConfiguration: Send + Sync {
    /// `hcloud`
    fn provider_type(&self) -> &'static str;

    fn name(&self) -> &str;
}

/// a configuration that proved to work, it holds the clients of the provider
#[async_trait]
pub trait ProviderRuntime: Send + Sync {
    /// registers the provisioners of the provider's resource types
    fn register(&self, registry: &mut Registry);
}

/// turns a configuration into a runtime: checks the credentials against the provider
#[async_trait]
pub trait ProviderManager<C: ProviderConfiguration> {
    type Runtime: ProviderRuntime;

    async fn validate(&self, configuration: &C) -> Result<Self::Runtime>;
}
