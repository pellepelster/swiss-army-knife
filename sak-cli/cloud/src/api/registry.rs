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
    /// to someone else fails here, also when the resource is not diffed. With `owner`, also one
    /// without the owner labels of the run
    async fn check_owned(
        &self,
        resource: &dyn AnyResource,
        context: &LookupProviders,
        owner: Option<&Labels>,
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
        owner: Option<&Labels>,
    ) -> Result<()> {
        let lookup = self.downcast(resource).as_lookup();
        let found = self.provisioner.lookup(&lookup, context).await?;
        if let Some(owner) = owner {
            identity::check(found, owner, &lookup.log_text())?;
        }
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

/// whether a plan stops at what an apply must not do: a duplicate, a recreate that is refused
#[derive(Clone, Copy, PartialEq, Eq)]
enum Gate {
    Apply,
    None,
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

    /// why the apply must not start on the diff: `Duplicate` for a duplicate; for a recreate,
    /// `RecreateRefused` when the resource is not recreatable, and as a recreate destroys first,
    /// `NoOwner` without owner labels and `NotOwned` for a resource without them
    async fn refuse(&self, resource: &dyn AnyResource, diff: &ResourceDiff) -> Result<()> {
        if diff.status == DiffStatus::Duplicate {
            return Err(duplicate(diff));
        }
        if !diff.needs_recreate() {
            return Ok(());
        }
        if !self.recreatable.contains(&diff.resource) {
            return Err(Error::RecreateRefused {
                resource: diff.resource.log_text.clone(),
                changes: diff
                    .changes
                    .iter()
                    .filter(|change| change.triggers_recreate())
                    .map(|change| change.log_text())
                    .collect::<Vec<_>>()
                    .join(", "),
            });
        }
        let owner = self.owner()?;
        self.provisioner_of(resource)?
            .check_owned(resource, &self.context, Some(owner))
            .await
    }

    /// the owner labels, or why nothing may be destroyed
    fn owner(&self) -> Result<&Labels> {
        self.owner
            .as_ref()
            .filter(|owner| !owner.is_empty())
            .ok_or(Error::NoOwner)
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
            return Err(Error::DeclaredTwice(reference.to_string()));
        }
        if !self.provisioners.contains_key(&TypeId::of::<R>()) {
            return Err(Error::NoProvisioner(reference.to_string()));
        }
        self.resources.push(Box::new(resource));
        Ok(())
    }

    pub fn with<R: Resource>(mut self, resource: R) -> Result<Self> {
        self.add(resource)?;
        Ok(self)
    }

    /// the diff of every resource, dependencies first. A resource with a missing dependency is
    /// `ParentMissing`. A resource sees each dependency that is not up to date as pending. The
    /// plan is the gate of the apply: a duplicate, a recreate the registry may not do and a
    /// resource of someone else fail here, before anything changes
    pub async fn diff_all(&mut self) -> Result<Vec<ResourceDiff>> {
        Ok(self
            .plan(Gate::Apply)
            .await?
            .into_iter()
            .map(|(_, diff)| diff)
            .collect())
    }

    /// the diff of every resource as `diff_all` has it, without its gates: a duplicate and a
    /// recreate show as their status and changes. For what applies nothing of it, the state of a
    /// deployment in a list or the plan of a destroy; an apply plans with its gates again first
    pub async fn compare_all(&mut self) -> Result<Vec<ResourceDiff>> {
        Ok(self
            .plan(Gate::None)
            .await?
            .into_iter()
            .map(|(_, diff)| diff)
            .collect())
    }

    /// `diff_all` with the index of each resource; with `Gate::Apply` it stops at what the apply
    /// must not do
    async fn plan(&mut self, gate: Gate) -> Result<Vec<(usize, ResourceDiff)>> {
        self.context.clear_pending();
        let mut diffs: Vec<(usize, ResourceDiff)> = Vec::new();
        for index in self.order()? {
            let resource = self.resources[index].as_ref();
            let reference = resource.resource_ref();
            let parent_missing = resource.depends_on().iter().any(|dependency| {
                diffs.iter().any(|(_, diff)| {
                    diff.resource == *dependency && diff.status == DiffStatus::Missing
                })
            });
            let diff = if parent_missing {
                // not diffed, but a resource of its name that belongs to someone else stops the
                // plan here, before anything is created
                self.provisioner_of(resource)?
                    .check_owned(resource, &self.context, None)
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
            // a plan that would recreate a resource that must stay, or create a duplicate, fails
            // here, before the apply
            if gate == Gate::Apply {
                self.refuse(resource, &diff).await?;
            }
            if diff.status != DiffStatus::UpToDate {
                self.context.mark_pending(reference);
            }
            diffs.push((index, diff));
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
        // the whole plan first: a refused recreate, a duplicate or a resource of someone else
        // stops the apply before anything is created, not half way
        let plan = self.plan(Gate::Apply).await?;
        self.apply_plan(plan, on_event).await
    }

    /// `apply_all_with` on a plan made already: the diffs `diff_all` gave for the same resources,
    /// of this registry or of one declared alike. Nothing is diffed again but what depends on a
    /// change, as in `apply_all`. The gates hold: a duplicate or a recreate that is refused stops
    /// it before anything changes. A plan of other resources fails
    pub async fn apply_planned_with(
        &mut self,
        plan: Vec<ResourceDiff>,
        on_event: &mut (dyn FnMut(ApplyEvent) + Send),
    ) -> Result<Vec<ApplyResult>> {
        let order = self.order()?;
        let matches = plan.len() == order.len()
            && plan
                .iter()
                .zip(&order)
                .all(|(diff, &index)| diff.resource == self.resources[index].resource_ref());
        if !matches {
            return Err(Error::message(
                "the plan is not one of the resources of the registry",
            ));
        }
        for (diff, &index) in plan.iter().zip(&order) {
            self.refuse(self.resources[index].as_ref(), diff).await?;
        }
        self.apply_plan(order.into_iter().zip(plan).collect(), on_event)
            .await
    }

    /// applies `plan`, the diff of each resource with its index in the order of the dependencies
    async fn apply_plan(
        &mut self,
        plan: Vec<(usize, ResourceDiff)>,
        on_event: &mut (dyn FnMut(ApplyEvent) + Send),
    ) -> Result<Vec<ApplyResult>> {
        self.context.clear_pending();
        // what the plan found not up to date and what the apply changed: the plan's diff of a
        // resource that depends on one is stale, it is diffed again once that is applied
        let mut changed: HashSet<ResourceRef> = plan
            .iter()
            .filter(|(_, diff)| diff.status != DiffStatus::UpToDate)
            .map(|(_, diff)| diff.resource.clone())
            .collect();
        let mut results = Vec::new();
        for (index, planned) in plan {
            let resource = self.resources[index].as_ref();
            let reference = resource.resource_ref();
            on_event(ApplyEvent::Started(reference.clone()));
            let provisioner = self.provisioner_of(resource)?;
            let diff = if resource
                .depends_on()
                .iter()
                .any(|dependency| changed.contains(dependency))
            {
                let diff = provisioner.diff(resource, &self.context).await?;
                self.refuse(resource, &diff).await?;
                diff
            } else {
                planned
            };
            let applied = match diff.status {
                DiffStatus::UpToDate => Applied::Unchanged,
                DiffStatus::Missing | DiffStatus::ParentMissing => {
                    info!("creating {reference}");
                    provisioner.apply(resource, &self.context).await?;
                    Applied::Created
                }
                DiffStatus::Duplicate => return Err(duplicate(&diff)),
                DiffStatus::HasChanges if diff.needs_recreate() => {
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
            if applied != Applied::Unchanged {
                changed.insert(reference.clone());
                // what depends on it and is diffed again sees it
                self.context.mark_applied(reference.clone());
            }
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
        let order = self.order()?;
        // every resource first, in the order of the destroy: one of someone else stops it before
        // anything is gone, not with its dependents gone already
        for &index in order.iter().rev() {
            let resource = self.resources[index].as_ref();
            self.provisioner_of(resource)?
                .check_owned(resource, &self.context, Some(&owner))
                .await?;
        }
        let mut destroyed = Vec::new();
        for index in order.into_iter().rev() {
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
            .ok_or_else(|| Error::NoProvisioner(resource.resource_ref().to_string()))
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
            return Err(Error::Cycle(
                self.resources[index].resource_ref().to_string(),
            ));
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

/// the error of a duplicate, with the message that names it
fn duplicate(diff: &ResourceDiff) -> Error {
    Error::Duplicate {
        resource: diff.resource.to_string(),
        message: diff
            .duplicate_message
            .clone()
            .unwrap_or_else(|| "is duplicated".to_string()),
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
        /// missing names that another thing duplicates, as an ssh key of the same fingerprint
        duplicated: Mutex<HashSet<String>>,
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
            let applied: Vec<String> = resource
                .depends_on
                .iter()
                .filter(|dependency| context.applied_with_change(&dependency.resource_ref()))
                .map(|dependency| dependency.0.clone())
                .collect();
            let applied = if applied.is_empty() {
                String::new()
            } else {
                format!(" applied {applied:?}")
            };
            self.log.lock().unwrap().push(format!(
                "diff {} pending {pending:?}{applied}",
                resource.name
            ));
            let Some(runtime) = self.lookup(&resource.as_lookup(), context).await? else {
                if self.duplicated.lock().unwrap().contains(&resource.name) {
                    return Ok(ResourceDiff::duplicate(
                        resource,
                        "another thing is the same",
                    ));
                }
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
        // the plan first (the server not diffed, its parents are missing), then the apply; only
        // the server is diffed again, once its parents are there, and sees them applied
        assert_eq!(
            log(&cloud),
            vec![
                "diff key pending []",
                "diff volume pending []",
                "apply key",
                "apply volume",
                "diff server pending [] applied [\"key\", \"volume\"]",
                "apply server"
            ]
        );

        let results = registry.apply_all().await.unwrap();
        assert!(
            results
                .iter()
                .all(|result| result.applied == Applied::Unchanged)
        );
        // all up to date: the plan's diffs, nothing diffed twice
        assert_eq!(
            log(&cloud),
            vec![
                "diff key pending []",
                "diff volume pending []",
                "diff server pending []"
            ]
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
            vec!["diff key pending []", "destroy key", "apply key"]
        );
    }

    #[tokio::test]
    async fn a_recreate_without_owner_labels_stops_the_plan() {
        let cloud = Arc::new(Cloud::default());
        cloud
            .existing
            .lock()
            .unwrap()
            .insert("server".to_string(), (1, owner()));
        let strict = Thing {
            strict: true,
            ..thing("server", 2, &[])
        };
        let mut registry = Registry::new();
        registry.register::<Thing, Cloud>(cloud.clone());
        let mut registry = registry
            .with(thing("key", 1, &[]))
            .unwrap()
            .with(strict)
            .unwrap()
            .recreatable(Resource::resource_ref(&thing("server", 2, &[])));

        assert!(matches!(registry.diff_all().await, Err(Error::NoOwner)));
        assert!(matches!(registry.apply_all().await, Err(Error::NoOwner)));
        // the key before the server is not created either
        assert!(!log(&cloud).iter().any(|line| line.starts_with("apply")));
    }

    #[tokio::test]
    async fn a_duplicate_stops_the_plan_before_anything_is_applied() {
        let cloud = Arc::new(Cloud::default());
        cloud.duplicated.lock().unwrap().insert("key".to_string());
        let mut registry = registry(&cloud)
            .with(thing("volume", 1, &[]))
            .unwrap()
            .with(thing("key", 1, &[]))
            .unwrap();

        let error = registry.apply_all().await.unwrap_err();

        assert!(
            matches!(&error, Error::Duplicate { resource, message }
                if resource == "thing 'key'" && message == "another thing is the same"),
            "{error}"
        );
        assert!(!log(&cloud).iter().any(|line| line.starts_with("apply")));

        // the plan of an apply fails on it; a comparison, for a list or a destroy, shows it
        assert!(matches!(
            registry.diff_all().await,
            Err(Error::Duplicate { .. })
        ));
        let diffs = registry.compare_all().await.unwrap();
        assert!(
            diffs
                .iter()
                .any(|diff| diff.resource.name == "key" && diff.status == DiffStatus::Duplicate)
        );

        // a comparison handed to the apply keeps the gate
        assert!(matches!(
            registry.apply_planned_with(diffs, &mut |_| {}).await,
            Err(Error::Duplicate { .. })
        ));
        assert!(!log(&cloud).iter().any(|line| line.starts_with("apply")));
    }

    #[tokio::test]
    async fn a_plan_made_already_is_applied_without_diffing_it_again() {
        let cloud = Arc::new(Cloud::default());
        cloud
            .existing
            .lock()
            .unwrap()
            .insert("volume".to_string(), (1, owner()));
        let declare = || {
            registry(&cloud)
                .with(thing("server", 1, &["key", "volume"]))
                .unwrap()
                .with(thing("volume", 1, &[]))
                .unwrap()
                .with(thing("key", 1, &[]))
                .unwrap()
        };
        let plan = declare().diff_all().await.unwrap();
        log(&cloud);

        // another registry declared alike applies it: only the server, whose key is created, is
        // diffed again and sees the key applied with a change
        let results = declare()
            .apply_planned_with(plan, &mut |_| {})
            .await
            .unwrap();
        let applied: Vec<(&str, Applied)> = results
            .iter()
            .map(|result| (result.resource.name.as_str(), result.applied))
            .collect();
        assert_eq!(
            applied,
            vec![
                ("key", Applied::Created),
                ("volume", Applied::Unchanged),
                ("server", Applied::Created)
            ]
        );
        assert_eq!(
            log(&cloud),
            vec![
                "apply key",
                "diff server pending [] applied [\"key\"]",
                "apply server"
            ]
        );

        // a plan of other resources is refused before anything is applied
        let plan = registry(&cloud)
            .with(thing("key", 1, &[]))
            .unwrap()
            .diff_all()
            .await
            .unwrap();
        assert!(
            declare()
                .apply_planned_with(plan, &mut |_| {})
                .await
                .is_err()
        );
        assert!(!log(&cloud).iter().any(|line| line.starts_with("apply")));
    }

    #[tokio::test]
    async fn a_recreate_refused_stops_a_plan_made_already() {
        let cloud = Arc::new(Cloud::default());
        cloud
            .existing
            .lock()
            .unwrap()
            .insert("key".to_string(), (1, owner()));
        let strict = || Thing {
            strict: true,
            ..thing("key", 2, &[])
        };
        // planned where the key may be recreated, applied where it may not
        let plan = registry(&cloud)
            .recreatable(Resource::resource_ref(&strict()))
            .with(strict())
            .unwrap()
            .diff_all()
            .await
            .unwrap();
        let error = registry(&cloud)
            .with(strict())
            .unwrap()
            .apply_planned_with(plan, &mut |_| {})
            .await
            .unwrap_err();
        assert!(matches!(error, Error::RecreateRefused { .. }), "{error}");
        assert!(!log(&cloud).iter().any(|line| line.starts_with("destroy")));
    }

    #[tokio::test]
    async fn a_thing_of_someone_else_stops_the_destroy_before_anything_is_gone() {
        let cloud = Arc::new(Cloud::default());
        let mut registry = registry(&cloud)
            .with(thing("key", 1, &[]))
            .unwrap()
            .with(thing("server", 1, &["key"]))
            .unwrap();
        registry.apply_all().await.unwrap();
        log(&cloud);
        // the key, destroyed after the server, lost its owner label
        cloud.plant("key", 1);

        let error = registry.destroy_all().await.unwrap_err();

        assert!(
            matches!(&error, Error::NotOwned { resource, .. } if resource == "thing 'key'"),
            "{error}"
        );
        assert!(log(&cloud).is_empty());
        assert!(cloud.existing.lock().unwrap().contains_key("server"));
        // one whose lookup refuses it
        cloud
            .existing
            .lock()
            .unwrap()
            .insert("key".to_string(), (1, owner()));
        cloud.foreign.lock().unwrap().insert("key".to_string());
        assert!(matches!(
            registry.destroy_all().await,
            Err(Error::NotOwned { .. })
        ));
        assert!(cloud.existing.lock().unwrap().contains_key("server"));
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

        assert!(matches!(error, Error::NoOwner), "{error}");
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

        assert!(matches!(
            registry.add(thing("a", 1, &[])),
            Err(Error::DeclaredTwice(_))
        ));

        registry.add(thing("b", 1, &["a"])).unwrap();
        // a → b → a: a cycle through another resource, not only of one with itself
        assert!(matches!(registry.diff_all().await, Err(Error::Cycle(_))));

        let mut empty = Registry::new();
        assert!(matches!(
            empty.add(thing("a", 1, &[])),
            Err(Error::NoProvisioner(_))
        ));
    }
}
