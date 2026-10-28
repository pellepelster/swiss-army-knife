# Glossary

The canonical vocabulary of this repository. Use these terms in code, documentation and the
user interface. Use the same term for the same thing every time.

- **sak name**: The unique name of a swiss army knife deployment. Each deployment has one sak
  name. Two deployments cannot have the same sak name. Each cloud resource of a deployment has
  the label `sak.pelle.io/name=<sak name>` (refer to "Resource labeling" in
  [AGENT.md](AGENT.md)).
- **dns mode** (`dns_mode`): How SAK sets up DNS for a deployment. The mode `domain` manages `<root domain>` and
  `*.<root domain>`. The mode `subdomain` manages `<sak name>.<root domain>` and `*.<sak name>.<root domain>`. The
  default is `subdomain` (refer to [FDR-001](doc/fdr/FDR-001-dns-mode.md)).
- **base domain**: The domain the hosts of a deployment are below: the root domain in dns mode
  `domain`, `<sak name>.<root domain>` in dns mode `subdomain`. The frontend is at
  `sak.<base domain>`, the api at `api.<base domain>`, a bucket at `<bucket>.<base domain>`.
- **root domain** (`root_domain`): The domain under which a deployment serves its apps. The
  production deployment uses `pelle.io`. A local deployment uses `localhost`.
