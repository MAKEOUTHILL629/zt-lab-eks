# Controles de red de soporte (sección 7.2.3 y 7.2.7).

# Lista de prefijos administrada de VPC Lattice: el rango de enlace local por el que
# los pods alcanzan el plano de datos y por el que VPC Lattice contacta a los nodos.
data "aws_ec2_managed_prefix_list" "lattice" {
  name = "com.amazonaws.${var.region}.vpc-lattice"
}

# Grupo de seguridad de la asociación VPC - red de servicios: admite los puertos de
# los oyentes (443 del modo A, 8443 del modo B) solo desde las subredes privadas.
# Lo referencia la VpcAssociationPolicy (k8s/, variable ${SG_ASOCIACION}).
resource "aws_security_group" "asociacion_lattice" {
  name        = "${var.nombre}-asociacion-lattice"
  description = "Asociacion de la VPC con la red de servicios de VPC Lattice"
  vpc_id      = module.vpc.vpc_id

  ingress {
    description = "Modo A: HTTPS con firma SigV4"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = var.subredes_privadas
  }

  ingress {
    description = "Modo B: paso directo TLS (mTLS extremo a extremo)"
    from_port   = 8443
    to_port     = 8443
    protocol    = "tcp"
    cidr_blocks = var.subredes_privadas
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.nombre}-asociacion-lattice" }
}

# Zona DNS privada para el dominio personalizado del modo B. El registro CNAME
# pagos.zt-lab.internal se crea en politicas/, cuando ya existe el dominio que VPC
# Lattice asigna al servicio de pagos.
resource "aws_route53_zone" "interna" {
  name = var.zona_privada

  vpc {
    vpc_id = module.vpc.vpc_id
  }

  # El controlador puede crear otras asociaciones de VPC; no las revierte tofu.
  lifecycle {
    ignore_changes = [vpc]
  }
}
