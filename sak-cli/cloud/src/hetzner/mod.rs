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

/// the prefix of the labels of sak on the resources
pub const LABEL_NAMESPACE: &str = "sak.pelle.io";

/// the name of the deployment that a resource belongs to; every cloud resource of a deployment
/// has it
pub const NAME_LABEL: &str = "sak.pelle.io/name";

/// the hash of the password of a deployment, on every resource of it; sakctl checks a password
/// against it before it changes a deployment
pub const PASSWORD_HASH_LABEL: &str = "sak.pelle.io/password-hash";

/// the dns mode of a deployment, `domain` or `subdomain`, on every resource of it
pub const DNS_MODE_LABEL: &str = "sak.pelle.io/dns-mode";

/// the user mode of a deployment, `single` or `multi`, on every resource of it; a deploy keeps
/// it unless told otherwise
pub const USER_MODE_LABEL: &str = "sak.pelle.io/user-mode";

/// the acme ca of the certificates of a deployment: `staging` for the staging ca of let's
/// encrypt (tests), none for the default. On every resource of it; a deploy keeps it
pub const ACME_CA_LABEL: &str = "sak.pelle.io/acme-ca";

/// the version of sak that last created or deployed a deployment, `0.1.1`, on every resource of
/// it
pub const VERSION_LABEL: &str = "sak.pelle.io/version";

/// the root domain of a deployment, `pelle.io`, on every resource of it
pub const ROOT_DOMAIN_LABEL: &str = "sak.pelle.io/root-domain";

/// the hash of the fingerprints of the ssh keys that a server was created with
pub const SSH_KEYS_LABEL: &str = "sak.pelle.io/ssh-keys";

/// the hash of the user data (cloud-init) that a server was created with
pub const USER_DATA_LABEL: &str = "sak.pelle.io/user-data";

/// on the ssh key that sakctl creates and deletes to check a token; the next check finds a key
/// that a check left and removes it
pub const TOKEN_CHECK_LABEL: &str = "sak.pelle.io/token-check";

/// the configuration that the server was provisioned with, as a hash only sakctl can make (keyed
/// by the master key of the deployment). Set after a provisioning succeeded, removed before one
/// starts. sakctl keeps it on the server; the declaration of the server does not have it
pub const PROVISIONED_CONFIG_LABEL: &str = "sak.pelle.io/provisioned-config";

/// whether the provisioners or sakctl keep the label on a resource in addition to the declared
/// labels, chunks included
pub fn is_managed_label(key: &str) -> bool {
    key.starts_with(SSH_KEYS_LABEL)
        || key.starts_with(USER_DATA_LABEL)
        || key == PROVISIONED_CONFIG_LABEL
}

/// the label diff of a hetzner resource, without the labels that the provisioners keep
pub(crate) fn hetzner_label_diff(resource: &impl Labeled, runtime: &impl Labeled) -> Vec<DiffItem> {
    label_diff(
        resource.labels(),
        runtime.labels(),
        &[SSH_KEYS_LABEL, USER_DATA_LABEL, PROVISIONED_CONFIG_LABEL],
    )
}

impl From<api::ApiError> for crate::api::Error {
    fn from(error: api::ApiError) -> Self {
        crate::api::Error::Provider(Box::new(error))
    }
}
