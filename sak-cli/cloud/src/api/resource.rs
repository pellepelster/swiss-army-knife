use crate::api::lookup::Lookup;
use std::any::TypeId;
use std::collections::BTreeMap;
use std::fmt;
use std::hash::{Hash, Hasher};

/// the labels of a resource as declared and as the provider reports them
pub type Labels = BTreeMap<String, String>;

/// what a declared resource looks like at the provider, the result of a lookup or apply
pub trait Runtime: Send + Sync + 'static {}

/// resources and runtimes that carry labels, the label diff works on them
pub trait Labeled {
    fn labels(&self) -> &Labels;
}

/// a resource declared in code, the provisioner of its type reconciles it
pub trait Resource: Send + Sync + 'static {
    type Runtime: Runtime;
    type Lookup: Lookup<Runtime = Self::Runtime>;

    /// the name the resource is looked up by at the provider, unique per type
    fn name(&self) -> &str;

    /// the lookup of this resource, other resources refer to it through this
    fn as_lookup(&self) -> Self::Lookup;

    /// the resources this one needs applied first: every lookup it holds
    fn depends_on(&self) -> Vec<ResourceRef> {
        Vec::new()
    }

    /// the resource in log lines and diffs, e.g. `server 'backend'`
    fn log_text(&self) -> String {
        self.as_lookup().log_text()
    }

    fn resource_ref(&self) -> ResourceRef {
        self.as_lookup().resource_ref()
    }
}

/// identifies a resource across types: the type of its lookup and its name; the log text
/// rides along for messages and takes no part in equality
#[derive(Clone, Debug)]
pub struct ResourceRef {
    pub type_id: TypeId,
    pub name: String,
    pub log_text: String,
}

impl ResourceRef {
    pub fn of<L: Lookup>(name: &str, log_text: String) -> Self {
        ResourceRef {
            type_id: TypeId::of::<L>(),
            name: name.to_string(),
            log_text,
        }
    }
}

impl PartialEq for ResourceRef {
    fn eq(&self, other: &Self) -> bool {
        self.type_id == other.type_id && self.name == other.name
    }
}

impl Eq for ResourceRef {}

impl Hash for ResourceRef {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.type_id.hash(state);
        self.name.hash(state);
    }
}

impl fmt::Display for ResourceRef {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.log_text)
    }
}
