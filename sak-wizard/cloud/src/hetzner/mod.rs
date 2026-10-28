//! the resources of the Hetzner Cloud

pub mod api;
pub mod firewall;
pub mod labels;
pub mod primary_ip;
pub mod server;
pub mod ssh_key;
pub mod volume;

#[cfg(test)]
pub(crate) mod test_support;

use crate::api::{DiffItem, Labeled, label_diff};

/// the prefix of the labels the provisioners keep for themselves on the resources
pub const LABEL_NAMESPACE: &str = "sak";

/// the hash of the fingerprints of the ssh keys a server was created with
pub const SSH_KEYS_LABEL: &str = "sak/ssh-keys";

/// the hash of the user data (cloud-init) a server was created with
pub const USER_DATA_LABEL: &str = "sak/user-data";

/// the label diff of a hetzner resource, without the labels the provisioners keep
pub(crate) fn hetzner_label_diff(resource: &impl Labeled, runtime: &impl Labeled) -> Vec<DiffItem> {
    label_diff(
        resource.labels(),
        runtime.labels(),
        &[SSH_KEYS_LABEL, USER_DATA_LABEL],
    )
}

impl From<api::ApiError> for crate::api::Error {
    fn from(error: api::ApiError) -> Self {
        crate::api::Error::Provider(Box::new(error))
    }
}
