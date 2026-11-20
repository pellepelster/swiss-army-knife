use crate::api::{Labeled, Labels, Runtime};
use crate::hetzner::api::ssh_keys::SshKey;

#[derive(Clone, Debug)]
pub struct HetznerSshKeyRuntime {
    pub id: u64,
    pub name: String,
    /// md5, `99:fc:...`, as hetzner reports it
    pub fingerprint: String,
    pub public_key: String,
    pub labels: Labels,
}

impl From<SshKey> for HetznerSshKeyRuntime {
    fn from(key: SshKey) -> Self {
        HetznerSshKeyRuntime {
            id: key.id,
            name: key.name,
            fingerprint: key.fingerprint,
            public_key: key.public_key,
            labels: key.labels,
        }
    }
}

impl Runtime for HetznerSshKeyRuntime {}

impl crate::hetzner::lookup::NamedRuntime for HetznerSshKeyRuntime {
    const KIND: &'static str = "ssh key";
}

impl Labeled for HetznerSshKeyRuntime {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
