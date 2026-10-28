//! Declares cloud resources in code and reconciles them against the running state: a
//! resource is looked up, diffed (missing, changed, needs a recreate) and applied.
//!
//! `api` holds the provider independent model and the registry that runs a set of
//! resources, `hetzner` the resources of the Hetzner Cloud, `providers` the configuration
//! of a provider. examples/backend.rs declares a backend; PLAN.md has the layout.

pub mod api;
pub mod hetzner;
pub mod providers;
