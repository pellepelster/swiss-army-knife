locals {
  labels = {
    "sak" = "true"
  }

  # the volume shows up under its stable by-id name once attached, see hcloud_volume.linux_device
  data_device = "/dev/disk/by-id/scsi-0HC_Volume_${hcloud_volume.data.id}"

  ssh_dir = "${path.module}/.ssh"
}

# The key the controller is administered with (mise run ssh), it is written to
# .ssh/ next to the state, which holds it as well.
resource "tls_private_key" "client" {
  algorithm = "ED25519"
}

# The controller's ssh host key, pre-generated so its identity is known before it
# ever boots and the first connection can be verified against known_hosts.
resource "tls_private_key" "host" {
  algorithm = "ED25519"
}

resource "hcloud_ssh_key" "controller" {
  name       = "sak-backend"
  public_key = tls_private_key.client.public_key_openssh
  labels     = local.labels
}

# only ssh, http, https and ping are reachable from the internet, outbound is unrestricted
resource "hcloud_firewall" "controller" {
  name   = "sak-backend"
  labels = local.labels

  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "22"
    source_ips = ["0.0.0.0/0", "::/0"]
  }

  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "80"
    source_ips = ["0.0.0.0/0", "::/0"]
  }

  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "443"
    source_ips = ["0.0.0.0/0", "::/0"]
  }

  rule {
    direction  = "in"
    protocol   = "icmp"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
}

# The data lives outside the server's lifecycle: a replaced server is attached to
# the same volume and finds its data where the old one left it.
resource "hcloud_volume" "data" {
  name     = "sak-data"
  size     = var.data_volume_size
  location = var.location
  format   = "ext4"
  labels   = local.labels

  lifecycle {
    prevent_destroy = true
  }
}

# The addresses live outside the server's lifecycle: with auto_delete off a deleted
# or replaced server releases the ips instead of deleting them, and the next server
# comes up under the same addresses.
resource "hcloud_primary_ip" "controller_ipv4" {
  name        = "sak-backend-ipv4"
  location    = var.location
  type        = "ipv4"
  auto_delete = false
  labels      = local.labels
}

moved {
  from = hcloud_primary_ip.controller
  to   = hcloud_primary_ip.controller_ipv4
}

resource "hcloud_primary_ip" "controller_ipv6" {
  name        = "sak-backend-ipv6"
  location    = var.location
  type        = "ipv6"
  auto_delete = false
  labels      = local.labels
}

resource "hcloud_server" "controller" {
  name         = "sak-backend"
  server_type  = var.server_type
  location     = var.location
  image        = var.image
  ssh_keys     = [hcloud_ssh_key.controller.id]
  firewall_ids = [hcloud_firewall.controller.id]
  labels       = local.labels

  public_net {
    ipv4_enabled = true
    ipv4         = hcloud_primary_ip.controller_ipv4.id
    ipv6_enabled = true
    ipv6         = hcloud_primary_ip.controller_ipv6.id
  }

  # the data volume is formatted and mounted by the pelle.sak.storage ansible role
  # (a changed user_data replaces the server)
  user_data = join("\n", ["#cloud-config", yamlencode({
    # install the pinned host key before sshd first starts, and only that one: the
    # image's pre-baked keys are deleted and no others are generated
    ssh_deletekeys  = true
    ssh_genkeytypes = []
    ssh_keys = {
      ed25519_private = tls_private_key.host.private_key_openssh
      ed25519_public  = tls_private_key.host.public_key_openssh
    }
  })])
}

resource "hcloud_volume_attachment" "data" {
  volume_id = hcloud_volume.data.id
  server_id = hcloud_server.controller.id
  automount = false
}

resource "local_sensitive_file" "client_private_key" {
  content              = tls_private_key.client.private_key_openssh
  filename             = "${local.ssh_dir}/id_ed25519"
  file_permission      = "0600"
  directory_permission = "0700"
}

resource "local_file" "client_public_key" {
  content              = tls_private_key.client.public_key_openssh
  filename             = "${local.ssh_dir}/id_ed25519.pub"
  file_permission      = "0644"
  directory_permission = "0700"
}

resource "local_file" "known_hosts" {
  content = join("", [
    for address in [hcloud_primary_ip.controller_ipv4.ip_address, hcloud_server.controller.ipv6_address] :
    "${address} ${trimspace(tls_private_key.host.public_key_openssh)}\n"
  ])
  filename             = "${local.ssh_dir}/known_hosts"
  file_permission      = "0644"
  directory_permission = "0700"
}

# the smtp credentials config/controller-config.yaml references, deployed as systemd
# EnvironmentFile of the controller unit
resource "local_sensitive_file" "controller_env" {
  content              = <<-EOT
    SAK_SMTP_HOST=${var.smtp_host}
    SAK_SMTP_PORT=${var.smtp_port}
    SAK_SMTP_USERNAME=${var.smtp_username}
    SAK_SMTP_PASSWORD=${var.smtp_password}
  EOT
  filename             = "${path.module}/.ansible/controller.env"
  file_permission      = "0600"
  directory_permission = "0700"
}

# everything ansible needs to reach the controller (mise run ansible:run)
resource "local_file" "ansible_inventory" {
  content = yamlencode({
    all = {
      hosts = {
        controller = {
          ansible_host                 = hcloud_primary_ip.controller_ipv4.ip_address
          ansible_user                 = "root"
          ansible_ssh_private_key_file = abspath(local_sensitive_file.client_private_key.filename)
          ansible_ssh_common_args      = "-o IdentitiesOnly=yes -o UserKnownHostsFile=${abspath(local_file.known_hosts.filename)} -o StrictHostKeyChecking=yes"
          storage_device               = local.data_device
          controller_env_src           = abspath(local_sensitive_file.controller_env.filename)
        }
      }
    }
  })
  filename             = "${path.module}/.ansible/inventory.yml"
  file_permission      = "0644"
  directory_permission = "0700"
}
