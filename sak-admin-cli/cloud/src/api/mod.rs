//! the provider-independent model: resources with their lookups and runtimes, the
//! provisioners that reconcile them, the diffs they report and the contexts they run in

pub mod context;
pub mod diff;
pub mod error;
pub mod identity;
pub mod lookup;
pub mod provisioner;
pub mod registry;
pub mod resource;

pub use context::{
    ApplyContext, DestroyContext, DiffContext, LookupContext, LookupContextExt, LookupProviders,
};
pub use diff::{ChangeKind, DiffItem, DiffStatus, ResourceDiff, label_diff};
pub use error::{Error, Result};
pub use lookup::{AnyLookup, Lookup};
pub use provisioner::{LookupProvider, Provisioner, destroy_resource};
pub use registry::{Applied, ApplyEvent, ApplyResult, Registry};
pub use resource::{Labeled, Labels, Resource, ResourceRef, Runtime};
