//! the provider independent model: resources with their lookups and runtimes, the
//! provisioners that reconcile them, the diff they report and the contexts they run in

pub mod context;
pub mod diff;
pub mod error;
pub mod lookup;
pub mod provisioner;
pub mod registry;
pub mod resource;

pub use context::{
    ApplyContext, DestroyContext, DiffContext, LookupContext, LookupContextExt, LookupProviders,
};
pub use diff::{DiffItem, DiffStatus, ResourceDiff, label_diff};
pub use error::{Error, Result};
pub use lookup::{AnyLookup, Lookup};
pub use provisioner::{Listable, LookupProvider, Provisioner};
pub use registry::{Applied, ApplyResult, Registry};
pub use resource::{Labeled, Labels, Resource, ResourceRef, Runtime};
