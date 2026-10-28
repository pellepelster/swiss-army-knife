# pelle_io.sak

Ansible collection that provisions the `sak-backend` server.

| Role                | Purpose                                                                           |
|---------------------|-----------------------------------------------------------------------------------|
| `pelle_io.sak.common`  | Base setup, enables automatic unattended upgrades, key based ssh login only, blocks the cloud metadata service (it serves the ssh host key) for everything but root |
| `pelle_io.sak.storage` | Formats the data volume (only if it has no filesystem yet) and mounts it at `/storage/data` |
| `pelle_io.sak.controller` | Pulls the `sak-backend` and `sak-ingress` images (caddy, denies everything until the controller configures it, storage in `/storage/data/ingress`, json logs in `/storage/data/ingress/log`) from ghcr.io on every run, restarts a container whose image changed, and runs them as quadlets; the s3 buckets live in `/storage/data/backend/data/s3`, readable by the ingress; both images and the host run as user `sak` (uid/gid 10000) |

`playbooks/server.yml` (`pelle_io.sak.server`) is what sakctl runs against the server of a
deployment right after creating it: it waits for ssh, then `common` and `storage`; the
`controller` role follows once its images and configuration come from the wizard.

The `storage` role needs `storage_device`, the data volume's device (sakctl sets it in the inventory of the run).
The `controller` role pulls its images (`controller_image`, `controller_ingress_image` and the two
log images) on every run, so a run always brings the newest `latest` of main. It needs
`sak_name`, `sak_dns_mode` (`domain` or `subdomain`) and `sak_root_domain`, which it writes into the controller
configuration it renders from `templates/controller-config.yaml.j2` (sakctl sets both in the
inventory of the run; the frontend is then at `sak.<base domain>`, the api at `api.<base domain>`),
and `controller_env_src`,
the environment file with the s3 credentials the configuration references (`$SAK_S3_ACCESS_KEY_ID`,
`$SAK_S3_SECRET_ACCESS_KEY`), the secret keys, the smtp and openrouter credentials and the addresses of the server;
sakctl does not write it yet.

```yaml
- hosts: controller
  roles:
    - pelle_io.sak.common
    - role: pelle_io.sak.storage
      vars:
        storage_device: /dev/disk/by-id/scsi-0HC_Volume_12345678
```
