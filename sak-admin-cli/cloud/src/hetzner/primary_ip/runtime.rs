use crate::api::{Labeled, Labels, Runtime};
use crate::hetzner::api::Location;
use crate::hetzner::api::primary_ips::{PrimaryIp, PrimaryIpType};

#[derive(Clone, Debug)]
pub struct HetznerPrimaryIpRuntime {
    pub id: u64,
    pub name: String,
    /// the address, a `/64` for ipv6
    pub ip: String,
    pub ip_type: PrimaryIpType,
    pub location: Location,
    /// the id of the server the ip is assigned to
    pub assignee_id: Option<u64>,
    pub auto_delete: bool,
    pub delete_protected: bool,
    pub labels: Labels,
}

impl From<PrimaryIp> for HetznerPrimaryIpRuntime {
    fn from(ip: PrimaryIp) -> Self {
        HetznerPrimaryIpRuntime {
            id: ip.id,
            name: ip.name,
            ip: ip.ip,
            ip_type: ip.ip_type,
            location: ip.location.name,
            assignee_id: ip.assignee_id,
            auto_delete: ip.auto_delete,
            delete_protected: ip.protection.delete,
            labels: ip.labels,
        }
    }
}

impl Runtime for HetznerPrimaryIpRuntime {}

impl crate::hetzner::lookup::NamedRuntime for HetznerPrimaryIpRuntime {
    const KIND: &'static str = "primary ip";
}

impl Labeled for HetznerPrimaryIpRuntime {
    fn labels(&self) -> &Labels {
        &self.labels
    }
}
