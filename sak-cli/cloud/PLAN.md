# sak-cloud: declarative cloud resources in Rust

A cargo crate that defines cloud resources in code and reconciles them against the
running state: lookup, diff (missing / changed / needs recreate), apply, destroy.
A port of the Kotlin `blcks-api` + `blcks-hetzner-cloud` + `blcks-provisioner-hetzner`
modules (`~/git/blcks`) and the provider layer of `solidblocks-cloud`.

## Scope of the first version

* provider: Hetzner Cloud only
* resources: ssh keys, servers, firewalls, volumes, primary ips (API `/primary_ips`; the
  address a server keeps across recreates when `auto_delete` is false; floating ips are
  out of scope)
* diff: `Missing`, `UpToDate`, `HasChanges(items)`, `Duplicate` (ssh key with the same
  fingerprint under another name); every item says whether it triggers a recreate
* cloud-init change detection: sha256 of the user data kept in a label on the server,
  compared on diff, a mismatch triggers a recreate (same mechanism as blcks)
* tests: the blcks provisioner tests ported 1:1 as integration tests against the real
  API, plus unit tests with wiremock that blcks never had. blcks has an API client for
  primary ips (`PrimaryIps.kt`) but no provisioner and no test; those are new, written
  after the pattern of the floating ip provisioner and its test

Out of scope: networks/subnets (skipped for good, the blcks server test attaches a
private ip, that part is dropped), load balancers, other providers (aws, libvirt). DNS
zones/records came in as step 8.

## Mapping of the Kotlin structure

| Kotlin (blcks / solidblocks-cloud)                     | Rust                                   |
|--------------------------------------------------------|----------------------------------------|
| `blcks-api` `BaseInfrastructureResource` / `Runtime`   | `api::resource::{Resource, Runtime}`   |
| `BaseResourceLookup`, `ResourceLookupProvider`         | `api::lookup::{Lookup, LookupProvider}` — the "datasource": read-only access to a resource, managed by this code or not |
| `ResourceProvisioner`, `DestroyableResourceProvisioner`| `api::provisioner::{Provisioner, Destroyable, Listable}` |
| `ResourceDiff`, `ResourceDiffItem`, `createLabelDiff`  | `api::diff`                            |
| `ProvisionerLookup/Diff/Apply/DestroyContext`          | `api::context`                         |
| `Result<T>` sealed (`Success`/`Error`)                 | `Result<T, Error>` with `thiserror`    |
| `ProvisionersRegistry`                                 | `api::registry::Registry` (orders by dependencies, diffs and applies a whole set) |
| solidblocks `providers/*` (configuration → runtime → provisioners) | `providers::{ProviderConfiguration, ProviderRuntime, ProviderManager}`, `providers::hetzner` |
| `blcks-hetzner-cloud` (ktor client)                    | `hetzner::api` (reqwest client)        |
| `blcks-provisioner-hetzner/cloud/<resource>/{X,XLookup,XRuntime,XProvisioner}.kt` | `hetzner::<resource>::{resource, lookup, runtime, provisioner}.rs` |
| `HetznerLabels` (chunked, hashed labels)               | `hetzner::labels::Labels`              |
| `blcks-infra-test` `HetznerTestContext`                | `tests/support/hetzner.rs` (labels every resource with a test id, deletes them on drop) |

## Crate layout

```
sak-cli/cloud/            the crate sak-cloud, next to the tauri app in src-tauri/ that will use it
  Cargo.toml                 standalone crate (no workspace yet, src-tauri keeps its own build)
  src/
    lib.rs
    api/
      mod.rs
      resource.rs            Resource, Runtime, Labeled traits; ResourceId = (TypeId, name)
      lookup.rs              Lookup<Runtime = ...>, LookupProvider, Listable
      provisioner.rs         Provisioner<R: Resource>: lookup / diff / apply / destroy
      diff.rs                ResourceDiff, DiffStatus, DiffItem, label_diff()
      context.rs             LookupContext (type-erased lookup by TypeId), DiffContext
                             (+ has_pending_change), ApplyContext, DestroyContext
      registry.rs            Registry: register provisioners, add resources, diff_all / apply_all
                             in dependency order, destroy_all in reverse
      error.rs               Error (Api, NotFound, Timeout, Duplicate, ...)
    providers/
      mod.rs                 ProviderConfiguration, ProviderRuntime, ProviderManager
      hetzner.rs             HetznerProviderConfiguration { name, token, default_location,
                             default_server_type } → HetznerProviderRuntime → provisioners +
                             lookup providers
    hetzner/
      mod.rs
      api/
        mod.rs               HetznerApi (reqwest, bearer token, json), pagination, retry on
                             RATE_LIMIT_EXCEEDED, wait_for_action()
        error.rs             HetznerApiError { code, message }, error codes enum
        model.rs             Location, ServerType enums, Meta/List envelopes, Action
        ssh_keys.rs servers.rs firewalls.rs volumes.rs primary_ips.rs
      labels.rs              Labels: chunk values > 62 chars, add_hashed_label, hash_label_matches
      ssh_key/ server/ firewall/ volume/ primary_ip/
        mod.rs resource.rs lookup.rs runtime.rs provisioner.rs
  examples/
    backend.rs               declares ssh key + volume + firewall + primary ip + server,
                             prints the diff, applies when --apply is given
  tests/
    support/mod.rs hetzner.rs
    hetzner_ssh_key.rs hetzner_server.rs hetzner_firewall.rs hetzner_volume.rs
    hetzner_primary_ip.rs
```

## Core traits (sketch)

```rust
pub trait Resource: Send + Sync + 'static {
    type Runtime: Runtime;
    type Lookup: Lookup<Runtime = Self::Runtime>;
    fn name(&self) -> &str;
    fn as_lookup(&self) -> Self::Lookup;
    fn depends_on(&self) -> Vec<ResourceRef>;     // (TypeId, name) of every lookup it holds
    fn log_text(&self) -> String;
    fn labels(&self) -> &BTreeMap<String, String>;
}

pub trait Lookup: Clone + Send + Sync + 'static {
    type Runtime: Runtime;
    fn name(&self) -> &str;
}

#[async_trait]
pub trait LookupProvider<L: Lookup>: Send + Sync {
    async fn lookup(&self, lookup: &L, ctx: &dyn LookupContext) -> Result<Option<L::Runtime>>;
}

#[async_trait]
pub trait Provisioner<R: Resource>: LookupProvider<R::Lookup> {
    async fn diff(&self, resource: &R, ctx: &dyn DiffContext) -> Result<ResourceDiff>;
    async fn apply(&self, resource: &R, ctx: &dyn ApplyContext) -> Result<R::Runtime>;
    async fn destroy(&self, lookup: &R::Lookup, ctx: &dyn DestroyContext) -> Result<bool>;
}

pub struct ResourceDiff { pub resource: String, pub status: DiffStatus, pub changes: Vec<DiffItem> }
pub enum DiffStatus { Missing, UpToDate, HasChanges, Duplicate(String), ParentMissing }
pub struct DiffItem { name, changed, triggers_recreate, missing, expected: Option<String>, actual: Option<String> }
impl ResourceDiff { fn needs_recreate(&self) -> bool; fn has_changes(&self) -> bool }
```

Design decisions that differ from Kotlin, and why:

* Lookups are held by value in resources (`HetznerServer { ssh_keys: Vec<HetznerSshKeyLookup> }`)
  instead of object references; the dependency graph comes from `depends_on()` as
  `(TypeId, name)` pairs. Keeps everything `Clone` and avoids `Arc<dyn>` graphs.
* The context's `lookup()` is type-erased: the registry keeps `HashMap<TypeId, Box<dyn
  AnyLookupProvider>>` and downcasts, so a server provisioner can resolve ssh keys and
  volumes through the context exactly as `context.lookup(it)` does in Kotlin.
* `has_pending_change(ref)` on the diff context is fed by the registry: when the ssh key
  diff reported changes, the server diff reports "ssh keys: known after apply" with
  recreate, as blcks does.
* `async-trait` for object safety, `tokio` runtime, `reqwest` + `serde` for the API (same
  versions as `sak-cli/src-tauri`), `sha2` + `hex` for the hashed labels, `md5` +
  `base64` for ssh fingerprints, `thiserror`, `tracing` for logs.
* The API client has no cache in the first version (blcks `HetznerApiCache`); a
  `Listable` provider does one list call. Add later when the diff of many resources gets slow.

## Diff rules per resource (ported from blcks)

* ssh key: fingerprint (recreate), labels; `Duplicate` when another key has the fingerprint
* volume: delete protection, labels (size and location are immutable; a difference is an error, not a diff item, as in blcks)
* primary ip: type ipv4/ipv6 (recreate), location (recreate), delete protection,
  `auto_delete` (always false, so the address outlives its server), labels. Created
  unassigned in a location (the api of blcks' days took a datacenter, today's takes a
  location and knows no datacenters anymore); the server picks it up.
* firewall: rules as sets, applied-to label selector, labels
* server: image (recreate), type (recreate), location (recreate), volumes by name
  (recreate), ssh key fingerprint hash label (recreate), user data hash label (recreate),
  primary ipv4/ipv6 by id against `public_net.ipv4.id` / `public_net.ipv6.id`, labels
  (the `sshKeys`/`userData` label prefixes are ignored by the label diff). A server with
  a primary ip must live in that ip's location.

Apply is idempotent: lookup, create when missing, then update labels / protection / rules /
assignments, then lookup again and return the runtime. A server is created with
`public_net: { ipv4: <id>, ipv6: <id> }` of its primary ips, so a recreate reuses the
addresses. Moving a primary ip to an existing server needs the server powered off: the
server apply powers off, assigns, powers on when the ids differ (and unassigns the ip from
another server first, as the firewall apply removes a stale selector). Recreate is not
automatic; the registry's `apply_all` refuses resources whose diff `needs_recreate()`
unless told to destroy first (same as blcks, which leaves that to the caller). Destroying
a server leaves a primary ip with `auto_delete = false` in place; `destroy_all` deletes
it after the server, in reverse dependency order.

## Tests

Unit (no token, run in CI):
* `labels`: port `HetznerLabelsTest` (chunking, hashed label match, key with `_` rejected)
* `diff::label_diff`: missing / changed / deleted labels, ignored prefixes
* API client against wiremock: pagination, rate-limit retry, action polling, error decoding
* each provisioner's `diff()` against a wiremock Hetzner that returns a canned server /
  key / firewall: this covers the "what is missing / what changed" logic without a cloud

Integration (need `HCLOUD_TOKEN`, skip themselves without one, so `cargo test` runs in CI):
* port `HetznerSSHKeyProvisionerTest`, `HetznerVolumeProvisionerTest`,
  `HetznerFirewallProvisionerTest`, `HetznerServerProvisionerTest` (without the private
  network part) with their exact expected values (fingerprints, hashes, diff item names)
* new `hetzner_primary_ip.rs` after the shape of `HetznerFloatingIpProvisionerTest`:
  missing → apply → up to date → type/protection/label diffs → destroy; and in the server
  test: create the server with the primary ip, destroy the server, recreate it, assert
  the same address
* `tests/support/hetzner.rs`: test id label on everything, cleanup of every resource with
  that label on drop, also after a failed assertion

mise task `cli:cloud:test` (in `sak-cli/mise.toml`): `cargo test` with `HCLOUD_TOKEN`
from `pass show hetzner/pelle.io/hcloud_api_token` when not set; the github workflow runs
it after `cli:test`, without a token.

## Steps

1. Scaffold the crate, `api` module, `hetzner::labels`, `diff::label_diff`, unit tests. (~400 lines) — done
2. `hetzner::api`: client, models, the five resource apis, actions, wiremock tests. (~900 lines) — done
3. ssh key resource + provisioner, the first integration test; this sets the pattern. (~250) — done
4. volume and primary ip. (~400) — done
5. firewall. (~250) — done
6. server with cloud-init and ssh key hash labels, volumes, primary ips in `public_net`
   and the power-off/assign/power-on path for an existing server. (~500) — done
7. `providers::hetzner`, `registry` (dependency order, `diff_all` report, `apply_all`),
   `examples/backend.rs`. (~400) — done
8. dns: `hetzner::api::zones` (zones and rrsets), `dns_zone` (a resource of its own, or
   the lookup of a zone that exists) and `dns_record` (an rrset whose values are literals,
   the public ipv4 of a server or the address of a primary ip), the test with a zone of
   its own. — done
9. Follow-ups, not in this plan: an api cache, a second provider. Networks and subnets
   are skipped.

Each step is one commit on main with its tests green; integration tests are run by hand
with the token before the commit of steps 3-6.
