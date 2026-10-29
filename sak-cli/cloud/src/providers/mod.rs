//! the providers of the resources. A configuration names a provider and holds its
//! credentials. Validation turns it into a runtime that registers the provisioners

pub mod hetzner;

use crate::api::Registry;
use crate::api::error::Result;
use async_trait::async_trait;

/// how to reach a provider, as declared: its type, a name that is unique among the providers
/// of the type, and what it needs (a token, defaults)
pub trait ProviderConfiguration: Send + Sync {
    /// `hcloud`
    fn provider_type(&self) -> &'static str;

    fn name(&self) -> &str;
}

/// a validated configuration; it holds the clients of the provider
#[async_trait]
pub trait ProviderRuntime: Send + Sync {
    /// registers the provisioners of the resource types of the provider
    fn register(&self, registry: &mut Registry);
}

/// turns a configuration into a runtime: it checks the credentials at the provider
#[async_trait]
pub trait ProviderManager<C: ProviderConfiguration> {
    type Runtime: ProviderRuntime;

    async fn validate(&self, configuration: &C) -> Result<Self::Runtime>;
}
