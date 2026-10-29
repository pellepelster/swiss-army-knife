use crate::api::resource::{Labels, ResourceRef, Runtime};
use std::any::Any;

/// the identity of a lookup without identity labels
static NO_IDENTITY: Labels = Labels::new();

/// refers to a resource by name, declared here or not: the data source of this crate. A
/// lookup provider turns it into the runtime, resources hold lookups of what they need
pub trait Lookup: Clone + Send + Sync + 'static {
    type Runtime: Runtime;

    fn name(&self) -> &str;

    /// the resource in log lines and diffs, e.g. `ssh key 'admin'`
    fn log_text(&self) -> String;

    /// the labels that, with the name, identify the resource (`api::identity`): a resource of
    /// the name without them is someone else's. None by default, the name alone then
    fn identity(&self) -> &Labels {
        &NO_IDENTITY
    }

    /// what must exist before this lookup resolves, for lookups of nested resources
    fn depends_on(&self) -> Vec<ResourceRef> {
        Vec::new()
    }

    fn resource_ref(&self) -> ResourceRef {
        ResourceRef::of::<Self>(self.name(), self.log_text())
    }
}

/// a lookup with its type erased, the contexts route it to the provider of its type
pub trait AnyLookup: Send + Sync {
    fn as_any(&self) -> &dyn Any;

    fn erased_ref(&self) -> ResourceRef;
}

impl<L: Lookup> AnyLookup for L {
    fn as_any(&self) -> &dyn Any {
        self
    }

    fn erased_ref(&self) -> ResourceRef {
        self.resource_ref()
    }
}
