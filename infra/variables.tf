variable "location" {
  description = "Hetzner Cloud location of the server and its data volume."
  type        = string
  default     = "nbg1"
}

variable "server_type" {
  description = "Hetzner Cloud server type of the controller."
  type        = string
  default     = "cx23"
}

variable "image" {
  description = "Image the controller is installed from."
  type        = string
  default     = "debian-13"
}

variable "data_volume_size" {
  description = "Size of the sak-data volume in GB."
  type        = number
  default     = 64
}

# set by `mise run tf` from pass (scaleway/pelle.io)
variable "smtp_host" {
  description = "SMTP server the controller sends the console's login links through."
  type        = string
}

variable "smtp_port" {
  description = "Port of the SMTP server, config/controller-config.yaml expects implicit TLS (465)."
  type        = number
}

variable "smtp_username" {
  description = "Username of the SMTP server."
  type        = string
  sensitive   = true
}

variable "smtp_password" {
  description = "Password of the SMTP server."
  type        = string
  sensitive   = true
}
