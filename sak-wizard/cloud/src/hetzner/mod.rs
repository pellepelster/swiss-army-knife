//! the resources of the Hetzner Cloud

pub mod labels;

/// the prefix of the labels the provisioners keep for themselves on the resources
pub const LABEL_NAMESPACE: &str = "sak";

/// the hash of the fingerprints of the ssh keys a server was created with
pub const SSH_KEYS_LABEL: &str = "sak/ssh-keys";

/// the hash of the user data (cloud-init) a server was created with
pub const USER_DATA_LABEL: &str = "sak/user-data";
