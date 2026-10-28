# Proveedores y versiones mínimas de la infraestructura del laboratorio.
# El laboratorio es efímero: se aprovisiona al iniciar cada sesión y se destruye
# al terminarla (tofu destroy), de acuerdo con la sección 6.6 de la monografía.
terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
    time = {
      source  = "hashicorp/time"
      version = "~> 0.12"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Proyecto = "zt-lab"
      Gestion  = "OpenTofu"
    }
  }
}
