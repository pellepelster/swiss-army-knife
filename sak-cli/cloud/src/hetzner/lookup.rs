use crate::api::{Labels, Lookup, Runtime};
use std::fmt;
use std::marker::PhantomData;

/// a runtime that a lookup finds by its name alone, a volume or a server; the dns record has a
/// lookup of its own
pub trait NamedRuntime: Runtime {
    /// the kind in the log text of the lookup, `volume` in `volume 'data'`
    const KIND: &'static str;
}

/// the lookup of a resource by its name, with the identity labels it is checked against. One
/// type per runtime: `HetznerVolumeLookup` is `NamedLookup<HetznerVolumeRuntime>`
pub struct NamedLookup<R> {
    pub name: String,
    /// the labels that, with the name, identify the resource (`api::identity`)
    pub identity: Labels,
    runtime: PhantomData<fn() -> R>,
}

impl<R> NamedLookup<R> {
    pub fn new(name: &str) -> Self {
        NamedLookup {
            name: name.to_string(),
            identity: Labels::new(),
            runtime: PhantomData,
        }
    }

    /// the lookup that also checks these labels, see `api::identity`
    pub fn with_identity(mut self, identity: Labels) -> Self {
        self.identity = identity;
        self
    }
}

impl<R: NamedRuntime> Lookup for NamedLookup<R> {
    type Runtime = R;

    fn name(&self) -> &str {
        &self.name
    }

    fn identity(&self) -> &Labels {
        &self.identity
    }

    fn log_text(&self) -> String {
        format!("{} '{}'", R::KIND, self.name)
    }
}

// by hand: a derive would ask the runtime for each of them
impl<R> Clone for NamedLookup<R> {
    fn clone(&self) -> Self {
        NamedLookup {
            name: self.name.clone(),
            identity: self.identity.clone(),
            runtime: PhantomData,
        }
    }
}

impl<R> PartialEq for NamedLookup<R> {
    fn eq(&self, other: &Self) -> bool {
        self.name == other.name && self.identity == other.identity
    }
}

impl<R> Eq for NamedLookup<R> {}

impl<R: NamedRuntime> fmt::Debug for NamedLookup<R> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("NamedLookup")
            .field("kind", &R::KIND)
            .field("name", &self.name)
            .field("identity", &self.identity)
            .finish()
    }
}
