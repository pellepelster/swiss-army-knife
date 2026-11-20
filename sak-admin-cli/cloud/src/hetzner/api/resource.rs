//! the requests that every kind of resource has: list, get, create, update and delete at
//! `<path>` and `<path>/<id>`, the actions at `<path>/<id>/actions/<command>` and their wait.
//! A kind is its type, its requests and its own actions

use crate::hetzner::api::model::{
    Action, ActionResponse, ActionsResponse, ChangeProtectionRequest, ListPage, ListQuery, Meta,
};
use crate::hetzner::api::{HetznerApi, Result};
use serde::de::{self, DeserializeOwned, IgnoredAny, MapAccess, Visitor};
use serde::{Deserialize, Deserializer, Serialize};
use std::fmt;
use std::marker::PhantomData;

/// a kind of resource of the api
pub trait HetznerResource: DeserializeOwned {
    /// the path of the kind and the key of its lists, `servers`
    const PATH: &'static str;
    /// the key of one resource in an answer, `server`
    const KEY: &'static str;
    type Create: Serialize;
    type Update: Serialize;
    /// what a create answers: the resource, or the resource with the actions that set it up
    type Created: CreateAnswer;
    /// what a delete answers: whether there was something to delete, or the action that
    /// deletes it
    type Deleted: DeleteAnswer;
}

/// a kind that has actions, polled at `<path>/actions/<id>`
pub trait WithActions: HetznerResource {}

/// a kind with a delete protection
pub trait Protectable: WithActions {
    /// whether the protection covers a rebuild too
    const REBUILD: bool = false;
}

/// the answer to a create
pub trait CreateAnswer: Sized {
    /// the json of the answer
    type Answer: DeserializeOwned;

    fn from_answer(answer: Self::Answer) -> Self;
}

/// a create that answers only the resource, `{ "ssh_key": {..} }`
impl<T: HetznerResource> CreateAnswer for T {
    type Answer = One<T>;

    fn from_answer(answer: One<T>) -> T {
        answer.0
    }
}

/// the answer to a delete
pub trait DeleteAnswer: Sized {
    /// whether the api answers a delete with an action
    const WITH_ACTION: bool;

    /// `existed` is false for a 404
    fn from_answer(existed: bool, action: Option<Action>) -> Self;
}

/// `false` when there was nothing to delete
impl DeleteAnswer for bool {
    const WITH_ACTION: bool = false;

    fn from_answer(existed: bool, _: Option<Action>) -> bool {
        existed
    }
}

/// the action that deletes the resource; `None` when it does not exist
impl DeleteAnswer for Option<Action> {
    const WITH_ACTION: bool = true;

    fn from_answer(_: bool, action: Option<Action>) -> Self {
        action
    }
}

/// the resource at `T::KEY` of an answer, `{ "server": {..} }`; the other keys are skipped
pub struct One<T>(T);

impl<'de, T: HetznerResource> Deserialize<'de> for One<T> {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> std::result::Result<Self, D::Error> {
        struct OneVisitor<T>(PhantomData<T>);

        impl<'de, T: HetznerResource> Visitor<'de> for OneVisitor<T> {
            type Value = One<T>;

            fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                write!(f, "an object with '{}'", T::KEY)
            }

            fn visit_map<A: MapAccess<'de>>(
                self,
                mut map: A,
            ) -> std::result::Result<One<T>, A::Error> {
                let mut item = None;
                while let Some(key) = map.next_key::<String>()? {
                    if key == T::KEY {
                        item = Some(map.next_value()?);
                    } else {
                        map.next_value::<IgnoredAny>()?;
                    }
                }
                item.map(One)
                    .ok_or_else(|| de::Error::missing_field(T::KEY))
            }
        }

        deserializer.deserialize_map(OneVisitor(PhantomData))
    }
}

/// a page of a list, `{ "servers": [..], "meta": {..} }`
pub struct Page<T> {
    items: Vec<T>,
    meta: Meta,
}

impl<'de, T: HetznerResource> Deserialize<'de> for Page<T> {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> std::result::Result<Self, D::Error> {
        struct PageVisitor<T>(PhantomData<T>);

        impl<'de, T: HetznerResource> Visitor<'de> for PageVisitor<T> {
            type Value = Page<T>;

            fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                write!(f, "an object with '{}' and 'meta'", T::PATH)
            }

            fn visit_map<A: MapAccess<'de>>(
                self,
                mut map: A,
            ) -> std::result::Result<Page<T>, A::Error> {
                let (mut items, mut meta) = (None, None);
                while let Some(key) = map.next_key::<String>()? {
                    if key == T::PATH {
                        items = Some(map.next_value()?);
                    } else if key == "meta" {
                        meta = Some(map.next_value()?);
                    } else {
                        map.next_value::<IgnoredAny>()?;
                    }
                }
                Ok(Page {
                    items: items.ok_or_else(|| de::Error::missing_field(T::PATH))?,
                    meta: meta.ok_or_else(|| de::Error::missing_field("meta"))?,
                })
            }
        }

        deserializer.deserialize_map(PageVisitor(PhantomData))
    }
}

impl<T: HetznerResource> ListPage for Page<T> {
    type Item = T;

    fn into_parts(self) -> (Vec<T>, Meta) {
        (self.items, self.meta)
    }
}

/// the api of one kind, `api.servers()`
pub struct ResourceApi<'a, T> {
    pub(crate) api: &'a HetznerApi,
    kind: PhantomData<fn() -> T>,
}

impl<'a, T: HetznerResource> ResourceApi<'a, T> {
    pub(crate) fn new(api: &'a HetznerApi) -> Self {
        ResourceApi {
            api,
            kind: PhantomData,
        }
    }

    fn path(id: u64) -> String {
        format!("{}/{id}", T::PATH)
    }

    pub async fn list(&self, query: &ListQuery) -> Result<Vec<T>> {
        self.api.list_all::<Page<T>>(T::PATH, query).await
    }

    pub async fn get(&self, id: u64) -> Result<Option<T>> {
        Ok(self
            .api
            .get::<One<T>>(&Self::path(id), &[])
            .await?
            .map(|one| one.0))
    }

    /// names are unique per project, so at most one
    pub async fn get_by_name(&self, name: &str) -> Result<Option<T>> {
        Ok(self.list(&ListQuery::name(name)).await?.into_iter().next())
    }

    pub async fn create(&self, request: &T::Create) -> Result<T::Created> {
        let answer = self.api.post(T::PATH, request).await?;
        Ok(T::Created::from_answer(answer))
    }

    pub async fn update(&self, id: u64, request: &T::Update) -> Result<T> {
        Ok(self.api.put::<One<T>, _>(&Self::path(id), request).await?.0)
    }

    pub async fn delete(&self, id: u64) -> Result<T::Deleted> {
        let path = Self::path(id);
        if T::Deleted::WITH_ACTION {
            let action = self.api.delete_with_action(&path).await?;
            Ok(T::Deleted::from_answer(action.is_some(), action))
        } else {
            let existed = self.api.delete(&path).await?;
            Ok(T::Deleted::from_answer(existed, None))
        }
    }
}

impl<T: WithActions> ResourceApi<'_, T> {
    fn action_path(id: u64, command: &str) -> String {
        format!("{}/{id}/actions/{command}", T::PATH)
    }

    /// an action without a body, `servers/<id>/actions/poweron`
    pub(crate) async fn action(&self, id: u64, command: &str) -> Result<Action> {
        Ok(self
            .api
            .post_empty::<ActionResponse>(&Self::action_path(id, command))
            .await?
            .action)
    }

    pub(crate) async fn action_with<B: Serialize>(
        &self,
        id: u64,
        command: &str,
        request: &B,
    ) -> Result<Action> {
        Ok(self
            .api
            .post::<ActionResponse, _>(&Self::action_path(id, command), request)
            .await?
            .action)
    }

    /// an action that the api runs once per resource it touches
    pub(crate) async fn actions_with<B: Serialize>(
        &self,
        id: u64,
        command: &str,
        request: &B,
    ) -> Result<Vec<Action>> {
        Ok(self
            .api
            .post::<ActionsResponse, _>(&Self::action_path(id, command), request)
            .await?
            .actions)
    }

    pub async fn wait_for_action(&self, action: &Action) -> Result<Action> {
        self.api.wait_for_action(T::PATH, action.id).await
    }

    /// waits for each action; the first error stops the wait
    pub async fn wait_for_actions(&self, actions: &[Action]) -> Result<()> {
        for action in actions {
            self.wait_for_action(action).await?;
        }
        Ok(())
    }
}

impl<T: Protectable> ResourceApi<'_, T> {
    pub async fn change_protection(&self, id: u64, delete: bool) -> Result<Action> {
        let request = ChangeProtectionRequest {
            delete,
            rebuild: T::REBUILD.then_some(delete),
        };
        self.action_with(id, "change_protection", &request).await
    }
}
