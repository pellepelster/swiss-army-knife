//! a set of declared resources and the provisioners for their types: diffed, applied and
//! destroyed as a whole, in the order their dependencies allow

use crate::api::context::LookupProviders;
use crate::api::diff::{DiffStatus, ResourceDiff};
use crate::api::error::{Error, Result};
use crate::api::provisioner::Provisioner;
use crate::api::resource::{Resource, ResourceRef};
use async_trait::async_trait;
use std::any::{Any, TypeId};
use std::collections::{HashMap, HashSet};
use std::marker::PhantomData;
use std::sync::Arc;
use tracing::info;

/// a declared resource with its type erased
trait AnyResource: Send + Sync {
    fn as_any(&self) -> &dyn Any;

    fn resource_ref(&self) -> ResourceRef;

    fn depends_on(&self) -> Vec<ResourceRef>;
}

impl<R: Resource> AnyResource for R {
    fn as_any(&self) -> &dyn Any {
        self
    }

    fn resource_ref(&self) -> ResourceRef {
        Resource::resource_ref(self)
    }

    fn depends_on(&self) -> Vec<ResourceRef> {
        Resource::depends_on(self)
    }
}

#[async_trait]
trait ErasedProvisioner: Send + Sync {
    async fn diff(
        &self,
        resource: &dyn AnyResource,
        context: &LookupProviders,
    ) -> Result<ResourceDiff>;

    async fn apply(&self, resource: &dyn AnyResource, context: &LookupProviders) -> Result<()>;

    async fn destroy(&self, resource: &dyn AnyResource, context: &LookupProviders) -> Result<bool>;
}

struct Erased<R, P> {
    provisioner: Arc<P>,
    resource: PhantomData<fn() -> R>,
}

impl<R: Resource, P: Provisioner<R>> Erased<R, P> {
    fn downcast<'a>(&self, resource: &'a dyn AnyResource) -> &'a R {
        resource
            .as_any()
            .downcast_ref::<R>()
            .expect("a resource is routed to the provisioner of its type")
    }
}

#[async_trait]
impl<R: Resource, P: Provisioner<R> + 'static> ErasedProvisioner for Erased<R, P> {
    async fn diff(
        &self,
        resource: &dyn AnyResource,
        context: &LookupProviders,
    ) -> Result<ResourceDiff> {
        self.provisioner
            .diff(self.downcast(resource), context)
            .await
    }

    async fn apply(&self, resource: &dyn AnyResource, context: &LookupProviders) -> Result<()> {
        self.provisioner
            .apply(self.downcast(resource), context)
            .await?;
        Ok(())
    }

    async fn destroy(&self, resource: &dyn AnyResource, context: &LookupProviders) -> Result<bool> {
        self.provisioner
            .destroy(&self.downcast(resource).as_lookup(), context)
            .await
    }
}

/// what `apply_all` did to a resource
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Applied {
    Created,
    Updated,
    Recreated,
    Unchanged,
}

#[derive(Clone, Debug)]
pub struct ApplyResult {
    pub resource: ResourceRef,
    pub applied: Applied,
}

/// the resources declared for a run and the provisioners that reconcile them. A
/// provisioner is registered once per resource type, a resource added once per name
#[derive(Default)]
pub struct Registry {
    context: LookupProviders,
    provisioners: HashMap<TypeId, Box<dyn ErasedProvisioner>>,
    resources: Vec<Box<dyn AnyResource>>,
}

impl Registry {
    pub fn new() -> Self {
        Self::default()
    }

    /// registers the provisioner of `R`, as the lookup provider of its lookups too
    pub fn register<R: Resource, P: Provisioner<R> + 'static>(&mut self, provisioner: Arc<P>) {
        self.context.register::<R::Lookup, P>(provisioner.clone());
        self.provisioners.insert(
            TypeId::of::<R>(),
            Box::new(Erased::<R, P> {
                provisioner,
                resource: PhantomData,
            }),
        );
    }

    /// the lookup providers of the run, for lookups outside a diff or apply
    pub fn context(&self) -> &LookupProviders {
        &self.context
    }

    pub fn add<R: Resource>(&mut self, resource: R) -> Result<()> {
        let reference = resource.resource_ref();
        if self
            .resources
            .iter()
            .any(|declared| declared.resource_ref() == reference)
        {
            return Err(Error::message(format!("{reference} is declared twice")));
        }
        if !self.provisioners.contains_key(&TypeId::of::<R>()) {
            return Err(Error::message(format!(
                "no provisioner registered for {reference}"
            )));
        }
        self.resources.push(Box::new(resource));
        Ok(())
    }

    pub fn with<R: Resource>(mut self, resource: R) -> Result<Self> {
        self.add(resource)?;
        Ok(self)
    }

    /// the diff of every resource, dependencies first; a resource whose dependency is
    /// missing is `ParentMissing`, one whose dependency has changes sees them as pending
    pub async fn diff_all(&mut self) -> Result<Vec<ResourceDiff>> {
        self.context.clear_pending();
        let mut diffs: Vec<ResourceDiff> = Vec::new();
        for index in self.order()? {
            let resource = self.resources[index].as_ref();
            let reference = resource.resource_ref();
            let parent_missing = resource.depends_on().iter().any(|dependency| {
                diffs
                    .iter()
                    .any(|diff| diff.resource == *dependency && diff.status == DiffStatus::Missing)
            });
            let diff = if parent_missing {
                ResourceDiff {
                    resource: reference.clone(),
                    status: DiffStatus::ParentMissing,
                    changes: Vec::new(),
                    duplicate_message: None,
                }
            } else {
                self.provisioner_of(resource)?
                    .diff(resource, &self.context)
                    .await?
            };
            if diff.status != DiffStatus::UpToDate {
                self.context.mark_pending(reference);
            }
            diffs.push(diff);
        }
        Ok(diffs)
    }

    /// applies every resource, dependencies first: created when missing, updated when
    /// changed, left alone when up to date. A resource whose changes need a recreate is
    /// destroyed and created again when `recreate` is set, refused otherwise; a duplicate
    /// is refused
    pub async fn apply_all(&mut self, recreate: bool) -> Result<Vec<ApplyResult>> {
        self.context.clear_pending();
        let mut results = Vec::new();
        for index in self.order()? {
            let resource = self.resources[index].as_ref();
            let reference = resource.resource_ref();
            let provisioner = self.provisioner_of(resource)?;
            let diff = provisioner.diff(resource, &self.context).await?;
            let applied = match diff.status {
                DiffStatus::UpToDate => Applied::Unchanged,
                DiffStatus::Missing | DiffStatus::ParentMissing => {
                    info!("creating {reference}");
                    provisioner.apply(resource, &self.context).await?;
                    Applied::Created
                }
                DiffStatus::Duplicate => {
                    return Err(Error::message(format!(
                        "{reference}: {}",
                        diff.duplicate_message.as_deref().unwrap_or("is duplicated")
                    )));
                }
                DiffStatus::HasChanges if diff.needs_recreate() => {
                    if !recreate {
                        return Err(Error::message(format!(
                            "{reference} needs a recreate, run with recreate allowed: {diff}"
                        )));
                    }
                    info!("recreating {reference}: {diff}");
                    provisioner.destroy(resource, &self.context).await?;
                    provisioner.apply(resource, &self.context).await?;
                    Applied::Recreated
                }
                DiffStatus::HasChanges => {
                    info!("updating {reference}: {diff}");
                    provisioner.apply(resource, &self.context).await?;
                    Applied::Updated
                }
            };
            results.push(ApplyResult {
                resource: reference,
                applied,
            });
        }
        Ok(results)
    }

    /// destroys every resource, dependents first; the refs of the resources destroyed
    pub async fn destroy_all(&mut self) -> Result<Vec<ResourceRef>> {
        let mut destroyed = Vec::new();
        for index in self.order()?.into_iter().rev() {
            let resource = self.resources[index].as_ref();
            let reference = resource.resource_ref();
            info!("destroying {reference}");
            if self
                .provisioner_of(resource)?
                .destroy(resource, &self.context)
                .await?
            {
                destroyed.push(reference);
            }
        }
        Ok(destroyed)
    }

    fn provisioner_of(&self, resource: &dyn AnyResource) -> Result<&dyn ErasedProvisioner> {
        self.provisioners
            .get(&resource.as_any().type_id())
            .map(Box::as_ref)
            .ok_or_else(|| {
                Error::message(format!(
                    "no provisioner registered for {}",
                    resource.resource_ref()
                ))
            })
    }

    /// the resources in an order that has every dependency before its dependents, as
    /// declared otherwise; a dependency not declared here is taken as existing already
    fn order(&self) -> Result<Vec<usize>> {
        let declared: HashMap<ResourceRef, usize> = self
            .resources
            .iter()
            .enumerate()
            .map(|(index, resource)| (resource.resource_ref(), index))
            .collect();
        let mut order = Vec::with_capacity(self.resources.len());
        let mut done = HashSet::new();
        let mut visiting = HashSet::new();
        for index in 0..self.resources.len() {
            self.visit(index, &declared, &mut done, &mut visiting, &mut order)?;
        }
        Ok(order)
    }

    fn visit(
        &self,
        index: usize,
        declared: &HashMap<ResourceRef, usize>,
        done: &mut HashSet<usize>,
        visiting: &mut HashSet<usize>,
        order: &mut Vec<usize>,
    ) -> Result<()> {
        if done.contains(&index) {
            return Ok(());
        }
        if !visiting.insert(index) {
            return Err(Error::message(format!(
                "{} depends on itself",
                self.resources[index].resource_ref()
            )));
        }
        for dependency in self.resources[index].depends_on() {
            if let Some(&dependency) = declared.get(&dependency) {
                self.visit(dependency, declared, done, visiting, order)?;
            }
        }
        visiting.remove(&index);
        done.insert(index);
        order.push(index);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::context::{ApplyContext, DestroyContext, DiffContext, LookupContext};
    use crate::api::diff::DiffItem;
    use crate::api::lookup::Lookup;
    use crate::api::provisioner::LookupProvider;
    use crate::api::resource::Runtime;
    use std::sync::Mutex;

    /// an in-memory provider: the names that exist, with a version; a resource is up to
    /// date when the versions match, a version change needs a recreate when `strict`
    #[derive(Default)]
    struct Cloud {
        existing: Mutex<HashMap<String, u32>>,
        log: Mutex<Vec<String>>,
    }

    #[derive(Clone)]
    struct ThingLookup(String);

    struct ThingRuntime(u32);

    impl Runtime for ThingRuntime {}

    impl Lookup for ThingLookup {
        type Runtime = ThingRuntime;

        fn name(&self) -> &str {
            &self.0
        }

        fn log_text(&self) -> String {
            format!("thing '{}'", self.0)
        }
    }

    struct Thing {
        name: String,
        version: u32,
        strict: bool,
        depends_on: Vec<ThingLookup>,
    }

    fn thing(name: &str, version: u32, depends_on: &[&str]) -> Thing {
        Thing {
            name: name.to_string(),
            version,
            strict: false,
            depends_on: depends_on
                .iter()
                .map(|name| ThingLookup(name.to_string()))
                .collect(),
        }
    }

    impl Resource for Thing {
        type Runtime = ThingRuntime;
        type Lookup = ThingLookup;

        fn name(&self) -> &str {
            &self.name
        }

        fn as_lookup(&self) -> ThingLookup {
            ThingLookup(self.name.clone())
        }

        fn depends_on(&self) -> Vec<ResourceRef> {
            self.depends_on.iter().map(Lookup::resource_ref).collect()
        }
    }

    #[async_trait]
    impl LookupProvider<ThingLookup> for Cloud {
        async fn lookup(
            &self,
            lookup: &ThingLookup,
            _: &dyn LookupContext,
        ) -> Result<Option<ThingRuntime>> {
            Ok(self
                .existing
                .lock()
                .unwrap()
                .get(&lookup.0)
                .map(|version| ThingRuntime(*version)))
        }
    }

    #[async_trait]
    impl Provisioner<Thing> for Cloud {
        async fn diff(&self, resource: &Thing, context: &dyn DiffContext) -> Result<ResourceDiff> {
            let pending: Vec<String> = resource
                .depends_on
                .iter()
                .filter(|dependency| context.has_pending_change(&dependency.resource_ref()))
                .map(|dependency| dependency.0.clone())
                .collect();
            self.log
                .lock()
                .unwrap()
                .push(format!("diff {} pending {pending:?}", resource.name));
            let Some(runtime) = self.lookup(&resource.as_lookup(), context).await? else {
                return Ok(ResourceDiff::missing(resource));
            };
            let mut changes = Vec::new();
            if runtime.0 != resource.version {
                changes.push(if resource.strict {
                    DiffItem::recreate("version", resource.version, runtime.0)
                } else {
                    DiffItem::changed("version", resource.version, runtime.0)
                });
            }
            Ok(ResourceDiff::from_changes(resource, changes))
        }

        async fn apply(&self, resource: &Thing, _: &dyn ApplyContext) -> Result<ThingRuntime> {
            self.log
                .lock()
                .unwrap()
                .push(format!("apply {}", resource.name));
            self.existing
                .lock()
                .unwrap()
                .insert(resource.name.clone(), resource.version);
            Ok(ThingRuntime(resource.version))
        }

        async fn destroy(&self, lookup: &ThingLookup, _: &dyn DestroyContext) -> Result<bool> {
            self.log
                .lock()
                .unwrap()
                .push(format!("destroy {}", lookup.0));
            Ok(self.existing.lock().unwrap().remove(&lookup.0).is_some())
        }
    }

    fn registry(cloud: &Arc<Cloud>) -> Registry {
        let mut registry = Registry::new();
        registry.register::<Thing, Cloud>(cloud.clone());
        registry
    }

    fn log(cloud: &Cloud) -> Vec<String> {
        cloud.log.lock().unwrap().drain(..).collect()
    }

    #[tokio::test]
    async fn applies_in_dependency_order_and_destroys_in_reverse() {
        let cloud = Arc::new(Cloud::default());
        let mut registry = registry(&cloud)
            .with(thing("server", 1, &["key", "volume"]))
            .unwrap()
            .with(thing("volume", 1, &[]))
            .unwrap()
            .with(thing("key", 1, &[]))
            .unwrap();

        let results = registry.apply_all(false).await.unwrap();

        let order: Vec<&str> = results
            .iter()
            .map(|result| result.resource.name.as_str())
            .collect();
        assert_eq!(order, vec!["key", "volume", "server"]);
        assert!(
            results
                .iter()
                .all(|result| result.applied == Applied::Created)
        );
        assert_eq!(
            log(&cloud),
            vec![
                "diff key pending []",
                "apply key",
                "diff volume pending []",
                "apply volume",
                "diff server pending []",
                "apply server"
            ]
        );

        let results = registry.apply_all(false).await.unwrap();
        assert!(
            results
                .iter()
                .all(|result| result.applied == Applied::Unchanged)
        );

        let destroyed = registry.destroy_all().await.unwrap();
        let order: Vec<&str> = destroyed
            .iter()
            .map(|reference| reference.name.as_str())
            .collect();
        assert_eq!(order, vec!["server", "volume", "key"]);
    }

    #[tokio::test]
    async fn diff_all_marks_changed_dependencies_pending_and_missing_parents() {
        let cloud = Arc::new(Cloud::default());
        cloud.existing.lock().unwrap().insert("key".to_string(), 1);
        let mut registry = registry(&cloud)
            .with(thing("key", 2, &[]))
            .unwrap()
            .with(thing("server", 1, &["key"]))
            .unwrap()
            .with(thing("other", 1, &["volume"]))
            .unwrap()
            .with(thing("volume", 1, &[]))
            .unwrap();

        let diffs = registry.diff_all().await.unwrap();

        let statuses: Vec<(&str, DiffStatus)> = diffs
            .iter()
            .map(|diff| (diff.resource.name.as_str(), diff.status))
            .collect();
        assert_eq!(
            statuses,
            vec![
                ("key", DiffStatus::HasChanges),
                ("server", DiffStatus::Missing),
                ("volume", DiffStatus::Missing),
                ("other", DiffStatus::ParentMissing),
            ]
        );
        assert!(log(&cloud).contains(&"diff server pending [\"key\"]".to_string()));
    }

    #[tokio::test]
    async fn a_recreate_is_refused_unless_allowed() {
        let cloud = Arc::new(Cloud::default());
        cloud.existing.lock().unwrap().insert("key".to_string(), 1);
        let strict = Thing {
            strict: true,
            ..thing("key", 2, &[])
        };
        let mut registry = registry(&cloud).with(strict).unwrap();

        let error = registry.apply_all(false).await.unwrap_err();
        assert!(
            error
                .to_string()
                .starts_with("thing 'key' needs a recreate"),
            "{error}"
        );

        let results = registry.apply_all(true).await.unwrap();
        assert_eq!(results[0].applied, Applied::Recreated);
        assert_eq!(
            log(&cloud),
            vec![
                "diff key pending []",
                "diff key pending []",
                "destroy key",
                "apply key"
            ]
        );
    }

    #[tokio::test]
    async fn refuses_duplicates_cycles_and_unknown_types() {
        let cloud = Arc::new(Cloud::default());
        let mut registry = registry(&cloud).with(thing("a", 1, &["b"])).unwrap();

        assert!(
            registry
                .add(thing("a", 1, &[]))
                .unwrap_err()
                .to_string()
                .contains("declared twice")
        );

        registry.add(thing("b", 1, &["a"])).unwrap();
        assert!(
            registry
                .diff_all()
                .await
                .unwrap_err()
                .to_string()
                .contains("depends on itself")
        );

        let mut empty = Registry::new();
        assert!(
            empty
                .add(thing("a", 1, &[]))
                .unwrap_err()
                .to_string()
                .contains("no provisioner")
        );
    }
}
