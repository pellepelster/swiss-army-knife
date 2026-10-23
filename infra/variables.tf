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
