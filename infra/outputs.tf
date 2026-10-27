output "controller_ipv4" {
  description = "Public IPv4 address of sak-backend."
  value       = hcloud_primary_ip.controller_ipv4.ip_address
}

output "controller_ipv6" {
  description = "Public IPv6 address of sak-backend."
  value       = hcloud_server.controller.ipv6_address
}

output "data_device" {
  description = "Device the sak-data volume is attached as on sak-backend."
  value       = local.data_device
}
