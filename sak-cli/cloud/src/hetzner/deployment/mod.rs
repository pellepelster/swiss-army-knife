//! the sak deployments of a cloud project: all resources with the name label, grouped by its
//! value, and the state of each deployment compared with its declared resource group.
//!
//! `discover` finds the deployments, `spec` declares the resources of one, `state` compares
//! them, `domain` holds the root domains and the dns mode, `settings` the user mode and the acme
//! ca, `records` the dns records of a deployment in the zones of the project

mod discover;
mod domain;
mod records;
mod settings;
mod spec;
mod state;

#[cfg(test)]
mod fixtures;

pub use discover::{DiscoveredDeployment, LeftOut, discover};
pub use domain::{
    DnsMode, DomainUse, RootDomain, RootDomainConflict, base_domain, frontend_host, root_domains,
};
pub use records::{
    DeploymentRecord, deployment_records, foreign_records, remove_records, stale_records,
};
pub use settings::{AcmeCa, UserMode};
pub use spec::{
    DATA_VOLUME_SUFFIX, DNS_LABEL_LEN, DataVolume, DeploymentSpec, MAX_NAME_LEN, RESOURCE_PREFIX,
    USER_DATA, check_name,
};
pub use state::{DeploymentState, DeploymentStatus, ResourceKind, ResourceState, state};

use crate::api::{Error, Result};
use crate::hetzner::api::HetznerApi;

/// sets each label `key` of the server `id` to its `value`, or removes it when the value is
/// `None`, in one request; the other labels of the server stay
pub async fn set_server_labels(
    api: &HetznerApi,
    id: u64,
    changes: &[(&str, Option<&str>)],
) -> Result<()> {
    api.servers()
        .set_labels(id, changes)
        .await?
        .ok_or_else(|| Error::message(format!("the server {id} is gone")))?;
    Ok(())
}
