# sak-cloud

Declares cloud resources in code and reconciles them with the running state. A resource is looked up at the
provider, compared (missing, changed, needs a recreate) and applied. A registry does this for a set of resources in
the order of their dependencies.

The provider is Hetzner Cloud: ssh keys, volumes, primary ips, firewalls, servers, dns zones and records. A server
keeps the hashes of its cloud-init user data and of its ssh keys in labels, so a change of one is a recreate. With a
primary ip, the recreated server keeps its address.

```rust
let hetzner = HetznerProviderRuntime::from_api(Arc::new(HetznerApi::new(&token)));
let key = HetznerSshKey::new("admin", &public_key);
let server = HetznerServer::new("backend", Location::hel1, ServerType::cx23, "#cloud-config\n")
    .with_ssh_keys(vec![key.as_lookup()]);
let registry = hetzner.registry().with(key)?.with(server)?;
print!("{}", diff::log_text(&registry.diff_all().await?));
registry.apply_all().await?;
```

## Safety gates

- **Recreate:** a resource that needs a recreate (e.g. another location) is destroyed and created again only when
  the registry declares it recreatable (`Registry::recreatable`). Otherwise the plan and the apply fail with
  `Error::RecreateRefused` before a change. An apply plans everything first, so it never stops half way.
  `apply_planned_with` applies a plan made before and keeps these gates.
- **Ownership:** identity labels (`with_identity`) and the name identify a resource, and every lookup checks them.
  A resource with the name but without the labels belongs to someone else: the lookup fails with `Error::NotOwned`,
  and no diff, apply or destroy touches it. A registry destroys only resources with its owner labels
  (`Registry::owned_by`).

## Run and test

- `HCLOUD_TOKEN=... SSH_PUBLIC_KEY=... cargo run --example backend -- --apply` runs `examples/backend.rs`, a backend
  with all resources.
- `mise run cli:test:unit` runs the unit tests (with those of sakctl). `mise run cli:test:e2e` runs the tests
  against the real api. They need `HCLOUD_TOKEN` (from pass when not set), and they label all they create with a test
  id and remove it at the end.

[PLAN.md](PLAN.md) has the layout and the mapping to the Kotlin code of blcks that this crate is a port of.
