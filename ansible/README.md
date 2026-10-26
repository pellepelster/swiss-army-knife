# pelle.sak

Ansible collection that provisions the `sak-controller` server.

| Role                | Purpose                                                                           |
|---------------------|-----------------------------------------------------------------------------------|
| `pelle.sak.common`  | Base setup, enables automatic unattended upgrades                                 |
| `pelle.sak.storage` | Formats the data volume (only if it has no filesystem yet) and mounts it at `/storage/data` |
| `pelle.sak.controller` | Installs the controller zip to `/storage/data/controller/app` as `sak-controller` systemd unit, and caddy (denies everything until the controller configures it, storage in `/storage/caddy`); the s3 buckets live in `/storage/data/controller/data/s3`, readable by caddy |

The `storage` role needs `storage_device`, the data volume's device (`terraform -chdir=infra output -raw data_device`).
The `controller` role needs `controller_dist_zip` (built by `./gradlew :sak-controller:distZip`) and `controller_config_src`,
the controller configuration `config/controller-config.yaml` (passed by `mise run ansible:run`), and `controller_env_src`,
the environment file with the s3 credentials the configuration references (`$SAK_S3_ACCESS_KEY_ID`,
`$SAK_S3_SECRET_ACCESS_KEY`), written to `infra/.ansible/controller.env` by `tf:apply` (set in the inventory).

```yaml
- hosts: controller
  roles:
    - pelle.sak.common
    - role: pelle.sak.storage
      vars:
        storage_device: /dev/disk/by-id/scsi-0HC_Volume_12345678
```
