//! the sak deployments at the providers: every resource with a name label, grouped by the name
//! and diffed against the resource group of a deployment. A create, deploy or destroy of a
//! deployment, planned and applied from its name and password

mod detect;
mod dns;
mod input;
mod manager;
mod password;
mod plan;
mod progress;
#[cfg(test)]
mod tests;

use crate::core::ansible_roles::{AnsibleRoles, AnsibleRolesProvisioner, is_provisioning};
use crate::core::container::ContainerRuntime;
use crate::core::error::{
    EXIT_DOMAIN_TAKEN, EXIT_MIXED_DNS_MODES, EXIT_NOT_OWNED, EXIT_RECREATE_REFUSED, EXIT_USAGE,
    SakctlError,
};
use crate::core::events::{AnsibleEvent, task_name};
use crate::core::keys::DerivedKey;
use crate::core::providers::{ProviderType, ProviderTypeResponse};
use crate::core::provision::ProvisionRun;
pub use detect::*;
pub use dns::*;
pub use input::*;
pub use manager::*;
pub use password::*;
pub use plan::*;
pub use progress::*;
use rust_i18n::t;
use sak_cloud::api::{Applied, ApplyEvent, DiffStatus, ResourceDiff};
use sak_cloud::api::{Lookup, Resource};
use sak_cloud::hetzner::api::{HetznerApi, Location, ServerType};
use sak_cloud::hetzner::deployment::{
    DataVolume, DeploymentRecord, DeploymentSpec, DeploymentState, DeploymentStatus,
    DiscoveredDeployment, DnsMode, MAX_NAME_LEN, ResourceKind, ResourceState, RootDomain,
    RootDomainConflict, check_name, deployment_records, discover, foreign_records, remove_records,
    root_domains, set_server_labels, stale_records, state,
};
use sak_cloud::hetzner::dns_record::{HetznerDnsRecordLookup, first_address};
use sak_cloud::hetzner::dns_zone::{HetznerDnsZoneListing, HetznerDnsZoneProvisioner};
use sak_cloud::hetzner::labels::HetznerLabels;
use sak_cloud::hetzner::{PASSWORD_HASH_LABEL, VERSION_LABEL};
use sak_cloud::providers::hetzner::HetznerProviderRuntime;
use serde::{Deserialize, Serialize};
use std::fmt;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeploymentResponse {
    pub(crate) provider: ProviderTypeResponse,
    pub(crate) name: String,
    pub(crate) status: DeploymentStatus,
    pub(crate) location: Option<Location>,
    /// the dns mode on its resources, none for a deployment created before it was kept
    pub(crate) dns_mode: Option<DnsMode>,
    /// the root domain on its resources, none for a deployment created before it was kept
    pub(crate) root_domain: Option<String>,
    /// where its frontend is, `https://sak.<base domain>`; none without a root domain
    pub(crate) frontend_url: Option<String>,
    pub(crate) resources: Vec<ResourceState>,
    /// why the deployment could not be compared with its declaration, with the status `unknown`
    pub(crate) error: Option<String>,
}

impl DeploymentResponse {
    fn of(
        provider: ProviderType,
        state: DeploymentState,
        deployment: &DiscoveredDeployment,
    ) -> Self {
        DeploymentResponse {
            provider: provider.response(),
            name: state.name,
            status: state.status,
            location: state.location,
            dns_mode: state.dns_mode,
            root_domain: deployment.root_domain(),
            frontend_url: deployment.frontend_url(),
            resources: state.resources,
            error: state.error,
        }
    }
}

#[cfg(test)]
mod window_fixtures;
