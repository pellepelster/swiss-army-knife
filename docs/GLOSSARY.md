# Glossary

The canonical vocabulary of this repository. Use these terms in code, documentation and the
user interface. Use the same term for the same thing every time.

- **name**: The unique name of a swiss army knife deployment. Each deployment has one name. Two
  deployments cannot have the same name. Each cloud resource of a deployment has
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
- **backup**: One file with all data of a deployment (the database, the files, the buckets, the state of the
  ingress and the logs), compressed and encrypted with the backup key, at
  `~/.sak/backups/<name>/<name>-<UTC time>.age.sak`. A **restore** replaces the data of the deployment of the same
  name with the data of a backup (refer to [FDR-007](fdr/FDR-007-backup.md)).
- **resource status**: What a plan or the state of a deployment says of one of its resources, compared with what the
  deployment declares. It is one of three: **up-to-date** (aktuell, the resource exists as declared), **missing**
  (fehlt, it does not exist, also when a resource it depends on does not) or **has changes** (hat Änderungen, it
  exists but differs). Messages use these words and no others for it, in the window and on the command line, the
  status first and then what is done to the resource: `volume 'sak-alpha-data' missing, will be created`. A resource
  found under another name is no status but an error that stops the plan.
- **deployment status**: The state of a whole deployment: **up-to-date** (aktuell) when every resource is,
  **incomplete** (unvollständig) when one is missing, else **has changes** (hat Änderungen).
