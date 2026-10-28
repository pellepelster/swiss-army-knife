terraform {
  required_version = ">= 1.10"

  backend "s3" {
    bucket       = "pelle-io-terraform-state"
    key          = "sak/terraform.tfstate"
    region       = "eu-central-1"
    use_lockfile = true
    encrypt      = true
  }

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.70"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.9"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.4"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }
}

# reads the api token from HCLOUD_TOKEN
provider "hcloud" {}
