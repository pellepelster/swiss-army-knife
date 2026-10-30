use crate::api::lookup::Lookup;
use std::any::TypeId;
use std::collections::BTreeMap;
use std::fmt;
use std::hash::{Hash, Hasher};

/// the labels of a resource, as declared or as the provider reports them
pub type Labels = BTreeMap<String, String>;

/// a resource as it is at the provider, the result of a lookup or an apply. The identity and
/// the owner are checked against its labels
pub trait Runtime: Labeled + Send + Sync + 'static {}

/// a resource or runtime with labels; the label diff compares them
pub trait Labeled {
    fn labels(&self) -> &Labels;

    /// the info labels among the labels: written, never compared, so a change of one alone is no
    /// diff (the version of SAK that created or changed a resource). Next to the identity labels,
    /// which every lookup checks
    fn info(&self) -> &Labels {
        static NONE: Labels = Labels::new();
        &NONE
    }
}

/// a resource declared in code; the provisioner of its type reconciles it
pub trait Resource: Send + Sync + 'static {
    type Runtime: Runtime;
    type Lookup: Lookup<Runtime = Self::Runtime>;

    /// the declared name. The name of its lookup identifies it and is unique per type; for a
    /// dns record the lookup name also has the zone and the type
    fn name(&self) -> &str;

    /// the lookup of this resource; other resources refer to it through the lookup
    fn as_lookup(&self) -> Self::Lookup;

    /// the resources to apply before this one: every lookup it holds
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

/// identifies a resource across types by the type of its lookup and its name. The log text is
/// for messages only; equality and hash ignore it
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
