# pelle_io.sak

Ansible collection that provisions the `sak-backend` server.

| Role                | Purpose                                                                           |
|---------------------|-----------------------------------------------------------------------------------|
| `pelle_io.sak.common`  | Base setup, enables automatic unattended upgrades, key based ssh login only, blocks the cloud metadata service (it serves the ssh host key) for everything but root |
| `pelle_io.sak.storage` | Formats the data volume (only if it has no filesystem yet) and mounts it at `/storage/data` (`sak_mount_dir`) |
| `pelle_io.sak.backend` | Pulls the `sak-backend` and `sak-ingress` images (caddy, denies everything until the backend configures it, storage in `/storage/data/<name>/ingress`, json logs in `/storage/data/<name>/ingress/log`) from ghcr.io on every run, restarts a container whose image changed, and runs them as quadlets; all data of the deployment lives below `/storage/data/<name>` (`sak_data_dir`, `<name>` is `sak_name`), the s3 buckets in `/storage/data/<name>/backend/data/s3`, readable by the ingress; both images and the host run as user `sak` (uid/gid 10000). |

`playbooks/server.yml` (`pelle_io.sak.server`) is what sakctl runs against the server of a
deployment right after creating it: it waits for ssh, then `common` and `storage`; the
`backend` role follows once its images and configuration come from the wizard.

The `storage` role needs `storage_device`, the data volume's device (sakctl sets it in the inventory of the run).
The `backend` role pulls its images (`backend_image`, `backend_ingress_image` and the two
log images) on every run, so a run always brings the newest `latest` of main. It needs
`sak_name`, `sak_dns_mode` (`domain` or `subdomain`), `sak_root_domain`, `sak_api_public_key`
(the public half of the key sakctl signs its requests with, never the key), and, for the e-mail login (multi user),
`sak_smtp_enabled`, `sak_admin_email`, `sak_smtp_tls` and `sak_smtp_starttls`; the login mails come from
`noreply@<root domain>`. It writes them into the
backend configuration it renders from `templates/backend-config.yaml.j2`; the frontend is then
at `sak.<base domain>`, the api at `api.<base domain>`. It also needs `backend_env_src`, the
environment file of the backend: `SAK_ADMIN_PASSWORD_HASH`, `SAK_IPV4`, `SAK_IPV6` and, with a mail
server, `SAK_SMTP_HOST`, `SAK_SMTP_PORT`, `SAK_SMTP_USERNAME` and `SAK_SMTP_PASSWORD`. sakctl writes
the inventory and this file for each run and runs the `server` playbook, which includes the role.

```yaml
- hosts: backend
  roles:
    - pelle_io.sak.common
    - role: pelle_io.sak.storage
      vars:
        storage_device: /dev/disk/by-id/scsi-0HC_Volume_12345678
```
