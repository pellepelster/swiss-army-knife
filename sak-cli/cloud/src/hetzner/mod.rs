//! the resources of the Hetzner Cloud

pub mod api;
pub mod deployment;
pub mod dns_record;
pub mod dns_zone;
pub mod firewall;
pub mod kind;
pub mod labels;
pub mod lookup;
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

/// the version of sak that last created or deployed a deployment, `0.1.1`, on every resource of
/// it
pub const VERSION_LABEL: &str = "sak.pelle.io/version";

/// the hash of the fingerprints of the ssh keys that a server was created with
pub const SSH_KEYS_LABEL: &str = "sak.pelle.io/ssh-keys";

/// the hash of the user data (cloud-init) that a server was created with
pub const USER_DATA_LABEL: &str = "sak.pelle.io/user-data";

/// on the ssh key that sakctl creates and deletes to check a token; the next check finds a key
/// that a check left and removes it
pub const TOKEN_CHECK_LABEL: &str = "sak.pelle.io/token-check";

/// whether the provisioners or sakctl keep the label on a resource in addition to the declared
/// labels, chunks included
pub fn is_managed_label(key: &str) -> bool {
    key.starts_with(SSH_KEYS_LABEL) || key.starts_with(USER_DATA_LABEL)
}

/// the label diff of a hetzner resource, without the managed labels (`is_managed_label`) and the
/// info labels of the resource (`Labeled::info`). The kinds of the labels of a deployment:
///
/// - identity: the name and the password hash, every lookup checks them, compared; a label of a
///   resource that is none of these kinds, e.g. a setting an older sakctl wrote, is a change that
///   deletes it
/// - managed: the hashes of the ssh keys and the user data; the provisioners and sakctl keep
///   them, never compared
/// - info: the version, written with the other labels, never compared
pub(crate) fn hetzner_label_diff(resource: &impl Labeled, runtime: &impl Labeled) -> Vec<DiffItem> {
    let info = resource.info();
    label_diff(resource.labels(), runtime.labels(), |key| {
        is_managed_label(key) || info.contains_key(key)
    })
}

impl From<api::ApiError> for crate::api::Error {
    fn from(error: api::ApiError) -> Self {
        crate::api::Error::Provider(Box::new(error))
    }
}

#[cfg(test)]
mod label_kind_tests {
    use super::*;
    use crate::api::Labels;
    use crate::hetzner::test_support::labels;

    struct Live(Labels);

    impl Labeled for Live {
        fn labels(&self) -> &Labels {
            &self.0
        }
    }

    #[test]
    fn the_managed_and_the_info_labels_are_no_diff_the_others_are() {
        let resource = volume::HetznerVolume::new("data", api::Location::nbg1, 16)
            .with_labels(labels(&[
                (NAME_LABEL, "alpha"),
                (PASSWORD_HASH_LABEL, "hash"),
            ]))
            .with_info(labels(&[(VERSION_LABEL, "0.1.2")]));
        let live = |pairs: &[(&str, &str)]| Live(labels(pairs));

        // another version, the hashes of the server
        let same = live(&[
            (NAME_LABEL, "alpha"),
            (PASSWORD_HASH_LABEL, "hash"),
            (VERSION_LABEL, "0.1.1"),
            ("sak.pelle.io/user-data_0", "abc"),
        ]);
        assert_eq!(hetzner_label_diff(&resource, &same), vec![]);
        // no version yet: a resource of a sakctl before the label
        let before = live(&[(NAME_LABEL, "alpha"), (PASSWORD_HASH_LABEL, "hash")]);
        assert_eq!(hetzner_label_diff(&resource, &before), vec![]);
        // an identity label is compared
        let other = live(&[(NAME_LABEL, "alpha"), (PASSWORD_HASH_LABEL, "another")]);
        assert_eq!(
            hetzner_label_diff(&resource, &other),
            vec![
                DiffItem::changed(format!("label '{PASSWORD_HASH_LABEL}'"), "hash", "another")
                    .of_label()
            ]
        );
        // a setting an older sakctl wrote as a label is deleted
        let older = live(&[
            (NAME_LABEL, "alpha"),
            (PASSWORD_HASH_LABEL, "hash"),
            ("sak.pelle.io/dns-mode", "domain"),
        ]);
        assert_eq!(
            hetzner_label_diff(&resource, &older),
            vec![DiffItem::removed("label 'sak.pelle.io/dns-mode'").of_label()]
        );
    }
}
