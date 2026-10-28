use crate::api::{Labeled, Labels, Lookup, Resource, ResourceRef};
use crate::hetzner::api::{Location, ServerType};
use crate::hetzner::labels::sha256_hex;
use crate::hetzner::primary_ip::HetznerPrimaryIpLookup;
use crate::hetzner::server::{HetznerServerLookup, HetznerServerRuntime};
use crate::hetzner::ssh_key::HetznerSshKeyLookup;
use crate::hetzner::volume::HetznerVolumeLookup;

/// the cloud-init of a server: the text to create it with, or only the hash of it for a
/// server that exists already, read from its labels
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum UserData {
    Text(String),
    /// the sha256 of the text, a server cannot be created from it
    Hash(String),
}

impl UserData {
    pub fn text(text: &str) -> Self {
        UserData::Text(text.to_string())
    }

    /// the sha256 that goes into the label of the server
    pub fn hash(&self) -> String {
        match self {
            UserData::Text(text) => sha256_hex(text),
            UserData::Hash(hash) => hash.clone(),
        }
    }
}

/// a server created from an image with cloud-init user data. The user data and the
/// ssh keys only count at creation, so their hashes are kept in labels and a change of
/// either is a recreate; with primary ips the recreated server keeps its addresses
#[derive(Clone, Debug)]
pub struct HetznerServer {
    pub name: String,
    pub location: Location,
    pub server_type: ServerType,
    /// `debian-13`
    pub image: String,
    /// cloud-init, `#cloud-config\n...`
    pub user_data: UserData,
    pub ssh_keys: Vec<HetznerSshKeyLookup>,
    pub volumes: Vec<HetznerVolumeLookup>,
    /// the ipv4 primary ip; without one hetzner creates an address that goes with the server
    pub ipv4: Option<HetznerPrimaryIpLookup>,
    pub ipv6: Option<HetznerPrimaryIpLookup>,
    pub labels: Labels,
}

impl HetznerServer {
    pub fn new(name: &str, location: Location, server_type: ServerType, user_data: &str) -> Self {
        HetznerServer {
            name: name.to_string(),
            location,
            server_type,
            image: "debian-13".to_string(),
            user_data: UserData::text(user_data),
            ssh_keys: vec![],
            volumes: vec![],
            ipv4: None,
            ipv6: None,
            labels: Labels::new(),
        }
    }

    pub fn with_image(mut self, image: &str) -> Self {
        self.image = image.to_string();
        self
    }

    pub fn with_ssh_keys(mut self, ssh_keys: Vec<HetznerSshKeyLookup>) -> Self {
        self.ssh_keys = ssh_keys;
        self
    }

    pub fn with_volumes(mut self, volumes: Vec<HetznerVolumeLookup>) -> Self {
        self.volumes = volumes;
        self
    }

    pub fn with_ipv4(mut self, ipv4: HetznerPrimaryIpLookup) -> Self {
        self.ipv4 = Some(ipv4);
        self
    }

    pub fn with_ipv6(mut self, ipv6: HetznerPrimaryIpLookup) -> Self {
        self.ipv6 = Some(ipv6);
        self
    }

    pub fn with_labels(mut self, labels: Labels) -> Self {
        self.labels = labels;
        self
    }
}

impl Resource for HetznerServer {
    type Runtime = HetznerServerRuntime;
    type Lookup = HetznerServerLookup;

    fn name(&self) -> &str {
        &self.name
    }

    fn as_lookup(&self) -> HetznerServerLookup {
        HetznerServerLookup::new(&self.name)
    }

    fn depends_on(&self) -> Vec<ResourceRef> {
        let keys = self.ssh_keys.iter().map(Lookup::resource_ref);
        let volumes = self.volumes.iter().map(Lookup::resource_ref);
        let ips = self
            .ipv4
            .iter()
            .chain(self.ipv6.iter())
            .map(Lookup::resource_ref);
        keys.chain(volumes).chain(ips).collect()
    }
}

impl Labeled for HetznerServer {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
