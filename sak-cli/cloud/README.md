# sak-cloud

Declares cloud resources in code and reconciles them against the running state. A
resource is looked up at the provider, diffed (missing, changed, needs a recreate) and
applied; a registry does that for a whole set in the order of their dependencies.

Hetzner Cloud is the first provider: ssh keys, volumes, primary ips, firewalls, servers,
dns zones and records. A server keeps the hashes of its cloud-init user data and ssh keys in labels,
so a change of either shows up in the diff as a recreate, and with a primary ip the
recreated server keeps its address.

```rust
let hetzner = HetznerProviderRuntime::from_api(Arc::new(HetznerApi::new(&token)));
let key = HetznerSshKey::new("admin", &public_key);
let server = HetznerServer::new("backend", Location::hel1, ServerType::cx23, "#cloud-config\n")
    .with_ssh_keys(vec![key.as_lookup()]);
let registry = hetzner.registry().with(key)?.with(server)?;
print!("{}", diff::log_text(&registry.diff_all().await?));
registry.apply_all().await?;
```

A resource whose changes need a recreate (e.g. another location) is destroyed and created again
only when the registry declares it recreatable (`Registry::recreatable`); otherwise the plan and
the apply fail with `Error::RecreateRefused` before anything is changed. An apply runs the whole
plan first, so it never stops half way at such a resource; `apply_planned_with` applies a plan
made already and keeps these gates.

A resource can carry identity labels (`with_identity`): with its name they identify it, and every
lookup checks them. A resource of the name without them belongs to someone else; the lookup fails
with `Error::NotOwned`, so no diff, apply or destroy touches it. A registry destroys only
resources with its owner labels (`Registry::owned_by`) and refuses to destroy without them.

`examples/backend.rs` declares a backend with everything, `HCLOUD_TOKEN=... SSH_PUBLIC_KEY=...
cargo run --example backend -- --apply`.

`mise run cli:test:unit` runs the unit tests (with those of sakctl), `mise run
cli:test:e2e` the tests against the real api. They need `HCLOUD_TOKEN`
(taken from pass when not set) and label everything they create with a test id that is
cleaned up at the end. [PLAN.md](PLAN.md) has the layout and the mapping to the Kotlin
code of blcks this is a port of.
