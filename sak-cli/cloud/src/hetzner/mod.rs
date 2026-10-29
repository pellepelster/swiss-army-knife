//! the resources of the Hetzner Cloud

pub mod api;
pub mod deployment;
pub mod dns_record;
pub mod dns_zone;
pub mod firewall;
pub mod labels;
pub mod primary_ip;
pub mod server;
pub mod ssh_key;
pub mod volume;

#[cfg(test)]
pub(crate) mod test_support;

use crate::api::{DiffItem, Labeled, label_diff};

/// the prefix of the labels sak keeps for itself on the resources
pub const LABEL_NAMESPACE: &str = "sak.pelle.io";

/// the name, the unique name of the deployment a resource belongs to; every cloud
/// resource of a deployment carries it
pub const NAME_LABEL: &str = "sak.pelle.io/name";

/// the hash of the password of a deployment, on its server; the wizard checks a password
/// against it before it touches a deployment
pub const PASSWORD_HASH_LABEL: &str = "sak.pelle.io/password-hash";

/// the dns mode of a deployment, `domain` or `subdomain`, on every resource of it
pub const DNS_MODE_LABEL: &str = "sak.pelle.io/dns-mode";

/// the root domain of a deployment, `pelle.io`, on every resource of it
pub const ROOT_DOMAIN_LABEL: &str = "sak.pelle.io/root-domain";

/// the hash of the fingerprints of the ssh keys a server was created with
pub const SSH_KEYS_LABEL: &str = "sak.pelle.io/ssh-keys";

/// the hash of the user data (cloud-init) a server was created with
pub const USER_DATA_LABEL: &str = "sak.pelle.io/user-data";

/// whether a label is one of the hashes the provisioners keep, chunks included
pub fn is_managed_label(key: &str) -> bool {
    key.starts_with(SSH_KEYS_LABEL) || key.starts_with(USER_DATA_LABEL)
}

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
