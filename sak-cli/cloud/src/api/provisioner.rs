use crate::api::context::{ApplyContext, DestroyContext, DiffContext, LookupContext};
use crate::api::diff::ResourceDiff;
use crate::api::error::Result;
use crate::api::lookup::Lookup;
use crate::api::resource::Resource;
use async_trait::async_trait;

/// reads a resource of one type from the provider, by its lookup
#[async_trait]
pub trait LookupProvider<L: Lookup>: Send + Sync {
    /// `None` when the provider has no resource of that name
    async fn lookup(&self, lookup: &L, context: &dyn LookupContext) -> Result<Option<L::Runtime>>;
}

/// lists every resource of one type at the provider, as lookups
#[async_trait]
pub trait Listable<L: Lookup>: Send + Sync {
    async fn list(&self) -> Result<Vec<L>>;
}

/// reconciles resources of one type: `diff` says what apply would do, `apply` creates the
/// resource when missing and updates what can be updated in place, `destroy` removes it
#[async_trait]
pub trait Provisioner<R: Resource>: LookupProvider<R::Lookup> {
    async fn diff(&self, resource: &R, context: &dyn DiffContext) -> Result<ResourceDiff>;

    /// idempotent: applying an up to date resource changes nothing and returns its runtime
    async fn apply(&self, resource: &R, context: &dyn ApplyContext) -> Result<R::Runtime>;

    /// `false` when there was nothing to destroy
    async fn destroy(&self, lookup: &R::Lookup, context: &dyn DestroyContext) -> Result<bool>;
}
