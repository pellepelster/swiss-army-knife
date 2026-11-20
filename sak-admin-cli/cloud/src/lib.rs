//! Declares cloud resources in code and reconciles them with the provider: each resource is
//! looked up, diffed (missing, changed, needs a recreate), applied and destroyed.
//!
//! `api` holds the provider-independent model and the registry that runs a set of resources.
//! `hetzner` holds the resources of the Hetzner Cloud, `providers` the runtime of a
//! provider that registers its provisioners. examples/backend.rs declares a backend. PLAN.md has the layout.

pub mod api;
pub mod hetzner;
pub mod providers;
