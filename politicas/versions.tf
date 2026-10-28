# Tercer nivel de autorización (políticas de identidad de portal y pedidos) y registro
# CNAME del servicio de pagos. Se aplica DESPUÉS de desplegar las rutas de VPC Lattice,
# porque necesita los ARN y el dominio que el controlador asigna a cada servicio
# (sección 7.2.10, dependencia circular).
terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
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

# Estado de la etapa de infraestructura (backend local por defecto).
data "terraform_remote_state" "infra" {
  backend = "local"
  config = {
    path = "../infra/terraform.tfstate"
  }
}
