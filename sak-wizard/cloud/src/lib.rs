//! Declares cloud resources in code and reconciles them against the running state: a
//! resource is looked up, diffed (missing, changed, needs a recreate) and applied.
//!
//! `api` holds the provider independent model, `hetzner` the resources of the Hetzner
//! Cloud. See PLAN.md for the layout and the steps.

pub mod api;
pub mod hetzner;
