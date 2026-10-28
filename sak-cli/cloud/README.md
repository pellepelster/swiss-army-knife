# sak-cloud

Declares cloud resources in code and reconciles them against the running state. A
resource is looked up at the provider, diffed (missing, changed, needs a recreate) and
applied; a registry does that for a whole set in the order of their dependencies.

Hetzner Cloud is the first provider: ssh keys, volumes, primary ips, firewalls, servers,
dns zones and records. A server keeps the hashes of its cloud-init user data and ssh keys in labels,
so a change of either shows up in the diff as a recreate, and with a primary ip the
recreated server keeps its address.

```rust
let hetzner = HetznerProviderManager.validate(&HetznerProviderConfiguration::new("default", &token)).await?;
let key = HetznerSshKey::new("admin", &public_key);
let server = HetznerServer::new("backend", Location::hel1, ServerType::cx23, "#cloud-config\n")
    .with_ssh_keys(vec![key.as_lookup()]);
let mut registry = hetzner.registry().with(key)?.with(server)?;
print!("{}", diff::log_text(&registry.diff_all().await?));
registry.apply_all(false).await?;
```

`examples/backend.rs` declares a backend with everything, `HCLOUD_TOKEN=... SSH_PUBLIC_KEY=...
cargo run --example backend -- --apply`.

`mise run cli:cloud:test` runs the tests; those against the real api need `HCLOUD_TOKEN`
(taken from pass when not set) and label everything they create with a test id that is
cleaned up at the end. [PLAN.md](PLAN.md) has the layout and the mapping to the Kotlin
code of blcks this is a port of.
