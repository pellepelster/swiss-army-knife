use crate::api::{Labeled, Labels, Runtime};
use crate::hetzner::api::servers::{Server, ServerStatus};
use crate::hetzner::api::{Location, ServerType};

#[derive(Clone, Debug)]
pub struct HetznerServerRuntime {
    pub id: u64,
    pub name: String,
    pub status: ServerStatus,
    /// `unknown` when the image was deleted
    pub image: String,
    pub server_type: ServerType,
    pub location: Location,
    /// the ids of the attached volumes
    pub volumes: Vec<u64>,
    pub public_ipv4: Option<String>,
    pub public_ipv6: Option<String>,
    /// the ids of the primary ips of the public addresses
    pub ipv4_id: Option<u64>,
    pub ipv6_id: Option<u64>,
    pub labels: Labels,
}

impl HetznerServerRuntime {
    pub fn ssh_user(&self) -> &'static str {
        "root"
    }

    pub fn ssh_port(&self) -> u16 {
        22
    }
}

impl From<Server> for HetznerServerRuntime {
    fn from(server: Server) -> Self {
        HetznerServerRuntime {
            id: server.id,
            name: server.name.clone(),
            status: server.status,
            image: server.image_name().unwrap_or("unknown").to_string(),
            server_type: server.server_type.name,
            location: server.location.name,
            volumes: server.volumes.clone(),
            public_ipv4: server.public_net.ipv4.as_ref().map(|ip| ip.ip.clone()),
            public_ipv6: server.public_net.ipv6.as_ref().map(|ip| ip.ip.clone()),
            ipv4_id: server.public_net.ipv4.as_ref().and_then(|ip| ip.id),
            ipv6_id: server.public_net.ipv6.as_ref().and_then(|ip| ip.id),
            labels: server.labels,
        }
    }
}

impl Runtime for HetznerServerRuntime {}

impl crate::hetzner::lookup::NamedRuntime for HetznerServerRuntime {
    const KIND: &'static str = "server";
}

impl Labeled for HetznerServerRuntime {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
