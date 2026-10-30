//! the one provisioner of the kinds that are found by their name and have no dependencies: a
//! kind tells how to fetch, compare, create, update and delete its resource, the impls here do
//! the rest for every kind. The server and the dns record have provisioners of their own

use crate::api::identity;
use crate::api::{
    ApplyContext, DestroyContext, DiffContext, DiffItem, Error, Labeled, LookupContext,
    LookupProvider, Provisioner, Resource, ResourceDiff, Result,
};
use crate::hetzner::hetzner_label_diff;
use crate::hetzner::lookup::{NamedLookup, NamedRuntime};
use async_trait::async_trait;

/// a kind of hetzner resource, implemented by its provisioner. The provisioner of the kind is
/// the blanket impl of `Provisioner`: it finds the resource and checks its identity, reports
/// it missing or duplicate, adds the label diff, and finds it again after an apply
#[async_trait]
pub trait HetznerKind: Send + Sync + 'static {
    type Resource: Resource<Runtime = Self::Runtime, Lookup = NamedLookup<Self::Runtime>> + Labeled;
    type Runtime: NamedRuntime;

    /// the resource of the name as the api has it, before the identity check
    async fn fetch(&self, name: &str) -> Result<Option<Self::Runtime>>;

    /// what differs besides the labels; the blanket impl adds the label diff
    fn compare(&self, resource: &Self::Resource, runtime: &Self::Runtime) -> Result<Vec<DiffItem>>;

    /// for a missing resource: the message that names another resource the create would collide
    /// with. None by default
    async fn duplicate_of(&self, _resource: &Self::Resource) -> Result<Option<String>> {
        Ok(None)
    }

    /// creates the resource with its labels and waits for what the create started
    async fn create(&self, resource: &Self::Resource) -> Result<Self::Runtime>;

    /// changes in place what differs besides the name and the labels, also right after a create
    /// for what the create cannot set; changes nothing that is as declared
    async fn update(&self, _resource: &Self::Resource, _runtime: &Self::Runtime) -> Result<()> {
        Ok(())
    }

    /// whether the name and the labels need writing: the labels differ, the info labels
    /// included. A kind may add what it writes with them
    fn labels_differ(&self, resource: &Self::Resource, runtime: &Self::Runtime) -> bool {
        resource.labels() != runtime.labels()
    }

    /// writes the name and the labels of the resource
    async fn update_labels(&self, resource: &Self::Resource, runtime: &Self::Runtime)
    -> Result<()>;

    /// what goes before the delete: the delete protection, a detach, an unassign
    async fn prepare_destroy(&self, _runtime: &Self::Runtime) -> Result<()> {
        Ok(())
    }

    async fn delete(&self, runtime: &Self::Runtime) -> Result<()>;
}

/// the resource of the name; `NotOwned` when it lacks an identity label of the lookup
async fn find<K: HetznerKind>(
    kind: &K,
    lookup: &NamedLookup<K::Runtime>,
) -> Result<Option<K::Runtime>> {
    identity::check_lookup(kind.fetch(&lookup.name).await?, lookup)
}

#[async_trait]
impl<K: HetznerKind> LookupProvider<NamedLookup<K::Runtime>> for K {
    async fn lookup(
        &self,
        lookup: &NamedLookup<K::Runtime>,
        _: &dyn LookupContext,
    ) -> Result<Option<K::Runtime>> {
        find(self, lookup).await
    }
}

#[async_trait]
impl<K: HetznerKind> Provisioner<K::Resource> for K {
    async fn diff(&self, resource: &K::Resource, _: &dyn DiffContext) -> Result<ResourceDiff> {
        let Some(runtime) = find(self, &resource.as_lookup()).await? else {
            return Ok(match self.duplicate_of(resource).await? {
                Some(message) => ResourceDiff::duplicate(resource, message),
                None => ResourceDiff::missing(resource),
            });
        };
        let mut changes = self.compare(resource, &runtime)?;
        changes.extend(hetzner_label_diff(resource, &runtime));
        Ok(ResourceDiff::from_changes(resource, changes))
    }

    /// creates a missing resource, else writes the name and the labels only when they differ
    async fn apply(&self, resource: &K::Resource, _: &dyn ApplyContext) -> Result<K::Runtime> {
        let lookup = resource.as_lookup();
        match find(self, &lookup).await? {
            Some(runtime) => {
                self.update(resource, &runtime).await?;
                if self.labels_differ(resource, &runtime) {
                    self.update_labels(resource, &runtime).await?;
                }
            }
            // the create wrote the name and the labels
            None => {
                let runtime = self.create(resource).await?;
                self.update(resource, &runtime).await?;
            }
        }
        find(self, &lookup)
            .await?
            .ok_or_else(|| Error::message(format!("{} is gone after apply", resource.log_text())))
    }

    async fn destroy(&self, runtime: &K::Runtime, _: &dyn DestroyContext) -> Result<()> {
        self.prepare_destroy(runtime).await?;
        self.delete(runtime).await
    }
}

/// the label builders of a resource with `labels`, `identity` and `info` fields, and its
/// `Labeled`: `declared_labels!(HetznerVolume)`
macro_rules! declared_labels {
    ($resource:ty) => {
        impl $resource {
            pub fn with_labels(mut self, labels: $crate::api::Labels) -> Self {
                self.labels = labels;
                self
            }

            /// sets the identity labels and adds them to the labels
            pub fn with_identity(mut self, identity: $crate::api::Labels) -> Self {
                self.labels.extend(identity.clone());
                self.identity = identity;
                self
            }

            /// sets the info labels and adds them to the labels
            pub fn with_info(mut self, info: $crate::api::Labels) -> Self {
                self.labels.extend(info.clone());
                self.info = info;
                self
            }
        }

        impl $crate::api::Labeled for $resource {
            fn labels(&self) -> &$crate::api::Labels {
                &self.labels
            }

            fn info(&self) -> &$crate::api::Labels {
                &self.info
            }
        }
    };
}

pub(crate) use declared_labels;
