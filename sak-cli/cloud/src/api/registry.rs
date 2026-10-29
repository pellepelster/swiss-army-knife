//! a set of declared resources and the provisioners of their types. The registry diffs,
//! applies and destroys the set as a whole, in the order of the dependencies

use crate::api::context::LookupProviders;
use crate::api::diff::{DiffStatus, ResourceDiff};
use crate::api::error::{Error, Result};
use crate::api::identity;
use crate::api::lookup::Lookup;
use crate::api::provisioner::Provisioner;
use crate::api::resource::{Labels, Resource, ResourceRef};
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

    /// looks the resource up, checks it against the owner labels and destroys it; `false`
    /// when nothing has the name
    async fn destroy(
        &self,
        resource: &dyn AnyResource,
        context: &LookupProviders,
        owner: &Labels,
    ) -> Result<bool>;

    /// looks the resource up for the identity check only: a resource of the name that belongs
    /// to someone else fails here, also when the resource is not diffed
    async fn check_owned(
        &self,
        resource: &dyn AnyResource,
        context: &LookupProviders,
    ) -> Result<()>;
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

    async fn destroy(
        &self,
        resource: &dyn AnyResource,
        context: &LookupProviders,
        owner: &Labels,
    ) -> Result<bool> {
        let lookup = self.downcast(resource).as_lookup();
        // the lookup checks its identity labels; this checks the owner labels of the run too,
        // so a resource declared without its identity is not removed either
        let found = self.provisioner.lookup(&lookup, context).await?;
        let Some(runtime) = identity::check(found, owner, &lookup.log_text())? else {
            return Ok(false);
        };
        self.provisioner.destroy(&runtime, context).await?;
        Ok(true)
    }

    async fn check_owned(
        &self,
        resource: &dyn AnyResource,
        context: &LookupProviders,
    ) -> Result<()> {
        let lookup = self.downcast(resource).as_lookup();
        self.provisioner.lookup(&lookup, context).await?;
        Ok(())
    }
}

/// what `apply_all` did to a resource
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
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

/// what `apply_all_with` and `destroy_all_with` report while they run: a resource started, a
/// resource done
#[derive(Clone, Debug)]
pub enum ApplyEvent {
    Started(ResourceRef),
    Applied(ApplyResult),
    /// by `destroy_all_with`: the resource is gone, or was gone already (`false`)
    Destroyed(ResourceRef, bool),
}

/// the resources declared for a run and the provisioners that reconcile them. There is one
/// provisioner per resource type and one resource per type and name
#[derive(Default)]
pub struct Registry {
    context: LookupProviders,
    provisioners: HashMap<TypeId, Box<dyn ErasedProvisioner>>,
    resources: Vec<Box<dyn AnyResource>>,
    /// the labels a resource must have before this registry destroys it, e.g. the name and the
    /// password hash of a deployment; without them nothing is destroyed
    owner: Option<Labels>,
    /// the resources that may be destroyed and created again when their changes need it. No
    /// other resource is recreated, e.g. a volume with data or a primary ip
    recreatable: HashSet<ResourceRef>,
}

impl Registry {
    pub fn new() -> Self {
        Self::default()
    }

    /// registers the provisioner of `R`, also as the lookup provider of its lookups
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

    /// the labels a resource must have before this registry destroys it, on a destroy and before
    /// a recreate; they must not be empty
    pub fn owned_by(mut self, owner: Labels) -> Self {
        self.owner = Some(owner);
        self
    }

    /// lets the resource be destroyed and created again when its changes need it. For any other
    /// resource, a plan or apply that needs a recreate fails with `RecreateRefused`
    pub fn recreatable(mut self, resource: ResourceRef) -> Self {
        self.recreatable.insert(resource);
        self
    }

    /// `RecreateRefused` for a diff that needs a recreate of a resource that is not recreatable
    fn refuse_recreate(&self, diff: &ResourceDiff) -> Result<()> {
        if diff.needs_recreate() && !self.recreatable.contains(&diff.resource) {
            return Err(Error::RecreateRefused {
                resource: diff.resource.log_text.clone(),
                changes: diff
                    .changes
                    .iter()
                    .filter(|change| change.triggers_recreate)
                    .map(|change| change.log_text())
                    .collect::<Vec<_>>()
                    .join(", "),
            });
        }
        Ok(())
    }

    /// the owner labels, or why nothing may be destroyed
    fn owner(&self) -> Result<&Labels> {
        self.owner
            .as_ref()
            .filter(|owner| !owner.is_empty())
            .ok_or_else(|| {
                Error::message(
                    "nothing is destroyed without the owner labels of the resources (owned_by)",
                )
            })
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

    /// the diff of every resource, dependencies first. A resource with a missing dependency is
    /// `ParentMissing`. A resource sees each dependency that is not up to date as pending
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
                // not diffed, but a resource of its name that belongs to someone else stops the
                // plan here, before anything is created
                self.provisioner_of(resource)?
                    .check_owned(resource, &self.context)
                    .await?;
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
            // a plan that would recreate a resource that must stay fails here, before the apply
            self.refuse_recreate(&diff)?;
            if diff.status != DiffStatus::UpToDate {
                self.context.mark_pending(reference);
            }
            diffs.push(diff);
        }
        Ok(diffs)
    }

    /// applies every resource, dependencies first: creates it when missing, updates it when
    /// changed, leaves it when up to date. When its changes need a recreate, a recreatable
    /// resource is destroyed and created again; any other fails. A duplicate fails
    pub async fn apply_all(&mut self) -> Result<Vec<ApplyResult>> {
        self.apply_all_with(&mut |_| {}).await
    }

    /// `apply_all` that tells `on_event` when each resource starts and is done
    pub async fn apply_all_with(
        &mut self,
        on_event: &mut (dyn FnMut(ApplyEvent) + Send),
    ) -> Result<Vec<ApplyResult>> {
        // the whole plan first: a refused recreate or a resource of someone else stops the apply
        // before anything is created, not half way
        self.diff_all().await?;
        self.context.clear_pending();
        let mut results = Vec::new();
        for index in self.order()? {
            let resource = self.resources[index].as_ref();
            let reference = resource.resource_ref();
            on_event(ApplyEvent::Started(reference.clone()));
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
                    self.refuse_recreate(&diff)?;
                    info!("recreating {reference}: {diff}");
                    provisioner
                        .destroy(resource, &self.context, self.owner()?)
                        .await?;
                    provisioner.apply(resource, &self.context).await?;
                    Applied::Recreated
                }
                DiffStatus::HasChanges => {
                    info!("updating {reference}: {diff}");
                    provisioner.apply(resource, &self.context).await?;
                    Applied::Updated
                }
            };
            let result = ApplyResult {
                resource: reference,
                applied,
            };
            on_event(ApplyEvent::Applied(result.clone()));
            results.push(result);
        }
        Ok(results)
    }

    /// destroys every resource, dependents first; returns the refs of the destroyed resources
    pub async fn destroy_all(&mut self) -> Result<Vec<ResourceRef>> {
        self.destroy_all_with(&mut |_| {}).await
    }

    /// `destroy_all` that tells `on_event` when each resource starts and is gone
    pub async fn destroy_all_with(
        &mut self,
        on_event: &mut (dyn FnMut(ApplyEvent) + Send),
    ) -> Result<Vec<ResourceRef>> {
        let owner = self.owner()?.clone();
        let mut destroyed = Vec::new();
        for index in self.order()?.into_iter().rev() {
            let resource = self.resources[index].as_ref();
            let reference = resource.resource_ref();
            info!("destroying {reference}");
            on_event(ApplyEvent::Started(reference.clone()));
            let gone = self
                .provisioner_of(resource)?
                .destroy(resource, &self.context, &owner)
                .await?;
            on_event(ApplyEvent::Destroyed(reference.clone(), gone));
            if gone {
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

    /// the resources with every dependency before its dependents, else in declared order. A
    /// dependency that is not declared here is taken as existing
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
        /// the version and the labels of each thing that exists
        existing: Mutex<HashMap<String, (u32, Labels)>>,
        /// names whose lookup fails with `NotOwned`, as a lookup with an identity does
        foreign: Mutex<HashSet<String>>,
        log: Mutex<Vec<String>>,
    }

    impl Cloud {
        /// a thing of the name that someone else created: without the owner label
        fn plant(&self, name: &str, version: u32) {
            self.existing
                .lock()
                .unwrap()
                .insert(name.to_string(), (version, Labels::new()));
        }
    }

    /// what every thing this registry creates carries, and what it destroys by
    fn owner() -> Labels {
        Labels::from([("owner".to_string(), "me".to_string())])
    }

    #[derive(Clone)]
    struct ThingLookup(String);

    struct ThingRuntime {
        name: String,
        version: u32,
        labels: Labels,
    }

    impl Runtime for ThingRuntime {}

    impl crate::api::resource::Labeled for ThingRuntime {
        fn labels(&self) -> &Labels {
            &self.labels
        }
    }

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
            if self.foreign.lock().unwrap().contains(&lookup.0) {
                return Err(Error::NotOwned {
                    resource: lookup.log_text(),
                    reason: "it has no label 'owner'".to_string(),
                });
            }
            Ok(self
                .existing
                .lock()
                .unwrap()
                .get(&lookup.0)
                .map(|(version, labels)| ThingRuntime {
                    name: lookup.0.clone(),
                    version: *version,
                    labels: labels.clone(),
                }))
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
            if runtime.version != resource.version {
                changes.push(if resource.strict {
                    DiffItem::recreate("version", resource.version, runtime.version)
                } else {
                    DiffItem::changed("version", resource.version, runtime.version)
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
                .insert(resource.name.clone(), (resource.version, owner()));
            Ok(ThingRuntime {
                name: resource.name.clone(),
                version: resource.version,
                labels: owner(),
            })
        }

        async fn destroy(&self, runtime: &ThingRuntime, _: &dyn DestroyContext) -> Result<()> {
            self.log
                .lock()
                .unwrap()
                .push(format!("destroy {}", runtime.name));
            self.existing.lock().unwrap().remove(&runtime.name);
            Ok(())
        }
    }

    fn registry(cloud: &Arc<Cloud>) -> Registry {
        let mut registry = Registry::new().owned_by(owner());
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

        let results = registry.apply_all().await.unwrap();

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
        // the plan first (the server not diffed, its parents are missing), then the apply
        assert_eq!(
            log(&cloud),
            vec![
                "diff key pending []",
                "diff volume pending []",
                "diff key pending []",
                "apply key",
                "diff volume pending []",
                "apply volume",
                "diff server pending []",
                "apply server"
            ]
        );

        let results = registry.apply_all().await.unwrap();
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
        cloud
            .existing
            .lock()
            .unwrap()
            .insert("key".to_string(), (1, owner()));
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
        cloud
            .existing
            .lock()
            .unwrap()
            .insert("key".to_string(), (1, owner()));
        let strict = Thing {
            strict: true,
            ..thing("key", 2, &[])
        };
        let mut registry = registry(&cloud).with(strict).unwrap();

        // not recreatable: neither the plan nor the apply recreate it
        let error = registry.diff_all().await.unwrap_err();
        assert!(
            matches!(&error, Error::RecreateRefused { resource, changes }
                if resource == "thing 'key'" && changes == "version should be '2' but was '1'"),
            "{error}"
        );
        assert!(matches!(
            registry.apply_all().await,
            Err(Error::RecreateRefused { .. })
        ));
        assert!(!log(&cloud).iter().any(|line| line.starts_with("destroy")));

        let mut registry = registry.recreatable(Resource::resource_ref(&thing("key", 2, &[])));
        let results = registry.apply_all().await.unwrap();
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
    async fn nothing_is_destroyed_without_owner_labels() {
        let cloud = Arc::new(Cloud::default());
        let mut registry = Registry::new();
        registry.register::<Thing, Cloud>(cloud.clone());
        let mut registry = registry.with(thing("key", 1, &[])).unwrap();
        registry.apply_all().await.unwrap();
        log(&cloud);

        let error = registry.destroy_all().await.unwrap_err();

        assert!(error.to_string().contains("owner labels"), "{error}");
        assert!(log(&cloud).is_empty());
        // empty owner labels count as none
        let mut empty = Registry::new().owned_by(Labels::new());
        empty.register::<Thing, Cloud>(cloud.clone());
        assert!(
            empty
                .with(thing("key", 1, &[]))
                .unwrap()
                .destroy_all()
                .await
                .is_err()
        );
        assert!(cloud.existing.lock().unwrap().contains_key("key"));
    }

    #[tokio::test]
    async fn a_thing_without_the_owner_labels_is_neither_destroyed_nor_recreated() {
        let cloud = Arc::new(Cloud::default());
        cloud.plant("key", 1);
        let mut destroying = registry(&cloud).with(thing("key", 1, &[])).unwrap();

        let error = destroying.destroy_all().await.unwrap_err();

        assert!(
            matches!(&error, Error::NotOwned { resource, .. } if resource == "thing 'key'"),
            "{error}"
        );
        assert!(!log(&cloud).iter().any(|line| line.starts_with("destroy")));

        let mut strict = thing("key", 2, &[]);
        strict.strict = true;
        let mut changed = registry(&cloud)
            .with(strict)
            .unwrap()
            .recreatable(Resource::resource_ref(&thing("key", 2, &[])));
        assert!(matches!(
            changed.apply_all().await,
            Err(Error::NotOwned { .. })
        ));
        assert!(!log(&cloud).iter().any(|line| line.starts_with("destroy")));
        assert_eq!(cloud.existing.lock().unwrap()["key"].0, 1);
        // nothing of the name: nothing to destroy, no error
        cloud.existing.lock().unwrap().clear();
        assert!(destroying.destroy_all().await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn a_thing_of_someone_else_stops_the_plan_also_when_its_parent_is_missing() {
        let cloud = Arc::new(Cloud::default());
        cloud.foreign.lock().unwrap().insert("server".to_string());
        let mut registry = registry(&cloud)
            .with(thing("key", 1, &[]))
            .unwrap()
            .with(thing("server", 1, &["key"]))
            .unwrap();

        // the key is missing, so the server is not diffed; its lookup still runs
        let error = registry.diff_all().await.unwrap_err();

        assert!(
            matches!(&error, Error::NotOwned { resource, .. } if resource == "thing 'server'"),
            "{error}"
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
