use crate::api::error::{Error, Result};
use crate::api::lookup::{AnyLookup, Lookup};
use crate::api::provisioner::LookupProvider;
use crate::api::resource::ResourceRef;
use async_trait::async_trait;
use std::any::{Any, TypeId};
use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::marker::PhantomData;
use std::sync::Arc;

/// resolves the lookups that a provisioner holds while it diffs, applies or destroys a resource.
/// Call the typed `lookup` of [LookupContextExt]; this method is its erased form
#[async_trait]
pub trait LookupContext: Send + Sync {
    async fn lookup_erased(
        &self,
        lookup: &dyn AnyLookup,
    ) -> Result<Option<Box<dyn Any + Send + Sync>>>;
}

/// the typed lookup on every context: `context.lookup(&ssh_key)` gives the ssh key runtime
pub trait LookupContextExt {
    fn lookup<'a, L: Lookup>(
        &'a self,
        lookup: &'a L,
    ) -> impl Future<Output = Result<Option<L::Runtime>>> + Send + 'a;
}

impl<C: LookupContext + ?Sized> LookupContextExt for C {
    // not an async fn: the explicit future carries the Send bound a provisioner needs
    #[allow(clippy::manual_async_fn)]
    fn lookup<'a, L: Lookup>(
        &'a self,
        lookup: &'a L,
    ) -> impl Future<Output = Result<Option<L::Runtime>>> + Send + 'a {
        async move {
            Ok(self.lookup_erased(lookup).await?.map(|runtime| {
                *runtime
                    .downcast::<L::Runtime>()
                    .expect("a lookup provider returns the runtime of its lookup type")
            }))
        }
    }
}

/// the context of a diff: the lookups, and which resources diffed before this one are not up
/// to date. A dependent resource then reports their values as known after the apply. While the
/// registry applies, nothing is pending; it tells which resources it applied with a change
pub trait DiffContext: LookupContext {
    fn has_pending_change(&self, resource: &ResourceRef) -> bool;

    /// whether the apply of this run created, updated or recreated `resource`; never outside an
    /// apply
    fn applied_with_change(&self, _resource: &ResourceRef) -> bool {
        false
    }
}

pub trait ApplyContext: LookupContext {}

pub trait DestroyContext: LookupContext {}

/// a lookup provider with its lookup type erased
#[async_trait]
trait ErasedLookupProvider: Send + Sync {
    async fn lookup_erased(
        &self,
        lookup: &dyn AnyLookup,
        context: &dyn LookupContext,
    ) -> Result<Option<Box<dyn Any + Send + Sync>>>;
}

struct Erased<L, P> {
    provider: Arc<P>,
    lookup: PhantomData<fn() -> L>,
}

#[async_trait]
impl<L: Lookup, P: LookupProvider<L> + 'static> ErasedLookupProvider for Erased<L, P> {
    async fn lookup_erased(
        &self,
        lookup: &dyn AnyLookup,
        context: &dyn LookupContext,
    ) -> Result<Option<Box<dyn Any + Send + Sync>>> {
        let lookup = lookup
            .as_any()
            .downcast_ref::<L>()
            .expect("a lookup is routed to the provider of its type");
        Ok(self
            .provider
            .lookup(lookup, context)
            .await?
            .map(|runtime| Box::new(runtime) as Box<dyn Any + Send + Sync>))
    }
}

/// the lookup providers of a run, one per lookup type. It is the context of the provisioners
/// outside a plan or apply of the registry: nothing is pending, nothing applied
#[derive(Default)]
pub struct LookupProviders {
    providers: HashMap<TypeId, Box<dyn ErasedLookupProvider>>,
}

impl LookupProviders {
    pub fn new() -> Self {
        Self::default()
    }

    /// registers `provider` for the lookups of type `L`; it replaces an earlier one
    pub fn register<L: Lookup, P: LookupProvider<L> + 'static>(&mut self, provider: Arc<P>) {
        self.providers.insert(
            TypeId::of::<L>(),
            Box::new(Erased::<L, P> {
                provider,
                lookup: PhantomData,
            }),
        );
    }

    pub fn with<L: Lookup, P: LookupProvider<L> + 'static>(mut self, provider: Arc<P>) -> Self {
        self.register::<L, P>(provider);
        self
    }
}

#[async_trait]
impl LookupContext for LookupProviders {
    async fn lookup_erased(
        &self,
        lookup: &dyn AnyLookup,
    ) -> Result<Option<Box<dyn Any + Send + Sync>>> {
        let provider = self
            .providers
            .get(&lookup.as_any().type_id())
            .ok_or_else(|| Error::NoLookupProvider(lookup.erased_ref().log_text))?;
        provider.lookup_erased(lookup, self).await
    }
}

impl DiffContext for LookupProviders {
    fn has_pending_change(&self, _: &ResourceRef) -> bool {
        false
    }
}

impl ApplyContext for LookupProviders {}

impl DestroyContext for LookupProviders {}

/// one plan or apply of the registry: the lookup providers, and what it found pending or
/// applied with a change so far. Each run starts empty; nothing of a plan leaks into the next
pub(crate) struct DiffRun<'a> {
    providers: &'a LookupProviders,
    pending: HashSet<ResourceRef>,
    applied: HashSet<ResourceRef>,
}

impl<'a> DiffRun<'a> {
    pub(crate) fn new(providers: &'a LookupProviders) -> Self {
        DiffRun {
            providers,
            pending: HashSet::new(),
            applied: HashSet::new(),
        }
    }

    /// only the registry marks what is pending, as it diffs
    pub(crate) fn mark_pending(&mut self, resource: ResourceRef) {
        self.pending.insert(resource);
    }

    /// only the registry marks what it applied with a change, as it applies
    pub(crate) fn mark_applied(&mut self, resource: ResourceRef) {
        self.applied.insert(resource);
    }
}

#[async_trait]
impl LookupContext for DiffRun<'_> {
    async fn lookup_erased(
        &self,
        lookup: &dyn AnyLookup,
    ) -> Result<Option<Box<dyn Any + Send + Sync>>> {
        self.providers.lookup_erased(lookup).await
    }
}

impl DiffContext for DiffRun<'_> {
    fn has_pending_change(&self, resource: &ResourceRef) -> bool {
        self.pending.contains(resource)
    }

    fn applied_with_change(&self, resource: &ResourceRef) -> bool {
        self.applied.contains(resource)
    }
}

impl ApplyContext for DiffRun<'_> {}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::resource::Runtime;

    #[derive(Clone)]
    struct KeyLookup(String);

    #[derive(Debug)]
    struct KeyRuntime {
        id: u64,
    }

    impl Runtime for KeyRuntime {}

    impl crate::api::resource::Labeled for KeyRuntime {
        fn labels(&self) -> &crate::api::resource::Labels {
            static NONE: crate::api::resource::Labels = crate::api::resource::Labels::new();
            &NONE
        }
    }

    impl Lookup for KeyLookup {
        type Runtime = KeyRuntime;

        fn name(&self) -> &str {
            &self.0
        }

        fn log_text(&self) -> String {
            format!("key '{}'", self.0)
        }
    }

    #[derive(Clone)]
    struct OtherLookup;

    impl Lookup for OtherLookup {
        type Runtime = KeyRuntime;

        fn name(&self) -> &str {
            "other"
        }

        fn log_text(&self) -> String {
            "other".to_string()
        }
    }

    struct KeyProvider;

    #[async_trait]
    impl LookupProvider<KeyLookup> for KeyProvider {
        async fn lookup(
            &self,
            lookup: &KeyLookup,
            _: &dyn LookupContext,
        ) -> Result<Option<KeyRuntime>> {
            Ok((lookup.0 == "known").then_some(KeyRuntime { id: 42 }))
        }
    }

    #[tokio::test]
    async fn routes_a_lookup_to_the_provider_of_its_type() {
        let context = LookupProviders::new().with::<KeyLookup, _>(Arc::new(KeyProvider));
        let context: &dyn DiffContext = &context;

        let runtime = context
            .lookup(&KeyLookup("known".into()))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(runtime.id, 42);
        assert!(
            context
                .lookup(&KeyLookup("unknown".into()))
                .await
                .unwrap()
                .is_none()
        );
    }

    #[tokio::test]
    async fn fails_a_lookup_without_provider() {
        let context = LookupProviders::new();

        let error = context.lookup(&OtherLookup).await.unwrap_err();
        assert_eq!(error.to_string(), "no lookup provider for other");
    }

    #[tokio::test]
    async fn a_run_tracks_what_is_pending_and_applied_and_looks_up_through_its_providers() {
        let providers = LookupProviders::new().with::<KeyLookup, _>(Arc::new(KeyProvider));
        let key = KeyLookup("a".into()).resource_ref();
        let mut run = DiffRun::new(&providers);

        assert!(!run.has_pending_change(&key));
        run.mark_pending(key.clone());
        assert!(run.has_pending_change(&key));
        assert!(!run.has_pending_change(&KeyLookup("b".into()).resource_ref()));
        assert!(!run.applied_with_change(&key));
        run.mark_applied(key.clone());
        assert!(run.applied_with_change(&key));
        let found = run.lookup(&KeyLookup("known".into())).await.unwrap();
        assert_eq!(found.unwrap().id, 42);

        // the next run, and the providers outside a run, know nothing of it
        let next = DiffRun::new(&providers);
        assert!(!next.has_pending_change(&key) && !next.applied_with_change(&key));
        assert!(!providers.has_pending_change(&key) && !providers.applied_with_change(&key));
    }
}
