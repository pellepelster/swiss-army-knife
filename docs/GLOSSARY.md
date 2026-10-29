# Glossary

The canonical vocabulary of this repository. Use these terms in code, documentation and the
user interface. Use the same term for the same thing every time.

- **sak name**: The unique name of a swiss army knife deployment. Each deployment has one sak
  name. Two deployments cannot have the same sak name. Each cloud resource of a deployment has
  the label `sak.pelle.io/name=<name>` (refer to "Cloud resources" in
  [ARCHITECTURE.md](ARCHITECTURE.md)).
- **dns mode** (`dns_mode`): How SAK sets up DNS for a deployment. The mode `domain` manages `<root domain>` and
  `*.<root domain>`. The mode `subdomain` manages `<name>.<root domain>` and `*.<name>.<root domain>`. The
  default is `subdomain` (refer to [FDR-001](fdr/FDR-001-dns-mode.md)).
- **user mode** (`user_mode`): Who signs in to a deployment. The mode `single` has one user, the admin, who
  signs in with the password of the deployment. The mode `multi` has users who sign in with a link by e-mail;
  the admin also signs in with the password. It needs a mail server and the address of the admin (refer to
  [FDR-005](fdr/FDR-005-login.md)).
- **base domain**: The domain the hosts of a deployment are below: the root domain in dns mode
  `domain`, `<name>.<root domain>` in dns mode `subdomain`. The frontend is at
  `sak.<base domain>`, the api at `api.<base domain>`, a bucket at `<bucket>.<base domain>`.
- **root domain** (`root_domain`): The domain under which a deployment serves its apps. The
  production deployment uses `pelle.io`. A local deployment uses `localhost`.
