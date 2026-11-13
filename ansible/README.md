# pelle_io.sak

The Ansible collection that provisions the server of a deployment. sakctl writes the inventory and the variables
for each run.

| Role | Purpose |
|---|---|
| `common` | The base setup: unattended upgrades, ssh login with a key only, nftables. Only root reaches the cloud metadata service (it serves the ssh host key). |
| `storage` | Formats the data volume `storage_device` (only if it has no filesystem) and mounts it at `/storage/data` (`sak_mount_dir`). |
| `backend` | Pulls the images `sak-backend`, `sak-ingress` (caddy) and the two log images on every run, restarts a container whose image changed, and runs them as quadlets. All data is below `/storage/data/<sak_name>`. The images and the host user `sak` have uid/gid 10000. |
| `role_markers` | The last role: writes the fingerprint of each applied role (`sak_role_markers`) to `/var/lib/sak/roles/<role>`, readable by root only. The playbook removes them first, so only a successful run leaves them. The plan of sakctl compares them to find a role that must run again. |

`playbooks/server.yml` is the playbook that sakctl runs: it waits for ssh, then runs all roles.
`playbooks/backend.yml` runs the same roles without the wait and the markers.

## Variables of the backend role

- The image tag is `sak_image_tag`, by default `sak_version`. sakctl sets its own version, so a server runs the
  version of the sakctl that deployed it. `SAK_DOCKER_IMAGE_TAG` of sakctl sets another tag, e.g. `<version>-rc`.
- `templates/backend-config.yaml.j2` gets `sak_name`, `sak_dns_mode` (`domain` or `subdomain`), `sak_root_domain`,
  `sak_api_public_key` (the public half of the key that sakctl signs its requests with) and, for the e-mail login
  (multi user), `sak_smtp_enabled`, `sak_smtp_tls`, `sak_smtp_starttls` and `sak_admin_email`. The frontend is at
  `sak.<base domain>`, the api at `api.<base domain>`.
- `backend_env_src` is the environment file of the backend: `SAK_ADMIN_PASSWORD_HASH`, `SAK_IPV4`, `SAK_IPV6` and,
  with a mail server, `SAK_SMTP_HOST`, `SAK_SMTP_PORT`, `SAK_SMTP_USERNAME`, `SAK_SMTP_PASSWORD` and
  `SAK_SMTP_FROM` (by default `noreply@<root domain>`).
