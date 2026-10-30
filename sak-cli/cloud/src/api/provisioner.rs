use crate::api::context::{ApplyContext, DestroyContext, DiffContext, LookupContext};
use crate::api::diff::ResourceDiff;
use crate::api::error::Result;
use crate::api::lookup::Lookup;
use crate::api::resource::Resource;
use async_trait::async_trait;

/// reads a resource of one type from the provider by its lookup
#[async_trait]
pub trait LookupProvider<L: Lookup>: Send + Sync {
    /// `None` when the provider has no resource of that name
    async fn lookup(&self, lookup: &L, context: &dyn LookupContext) -> Result<Option<L::Runtime>>;
}

/// reconciles resources of one type: `diff` tells what `apply` would do, `apply` creates a
/// missing resource and updates what it can update in place, `destroy` removes it
#[async_trait]
pub trait Provisioner<R: Resource>: LookupProvider<R::Lookup> {
    async fn diff(&self, resource: &R, context: &dyn DiffContext) -> Result<ResourceDiff>;

    /// idempotent: for an up-to-date resource it changes nothing and returns the runtime
    async fn apply(&self, resource: &R, context: &dyn ApplyContext) -> Result<R::Runtime>;

    /// removes the resource that a lookup found. It gets only a runtime that was looked up and
    /// checked; a provisioner never removes a resource by its name alone
    async fn destroy(&self, runtime: &R::Runtime, context: &dyn DestroyContext) -> Result<()>;
}

/// looks the resource up (with the identity check) and destroys it; `false` when nothing has
/// the name. For a destroy outside a registry, e.g. in the tests against the api
pub async fn destroy_resource<R: Resource, P: Provisioner<R>>(
    provisioner: &P,
    resource: &R,
    context: &dyn DestroyContext,
) -> Result<bool> {
    match provisioner.lookup(&resource.as_lookup(), context).await? {
        Some(runtime) => {
            provisioner.destroy(&runtime, context).await?;
            Ok(true)
        }
        None => Ok(false),
    }
}
