locals {
  cuenta  = data.terraform_remote_state.infra.outputs.cuenta
  roles   = data.terraform_remote_state.infra.outputs.nombres_roles_carga
  zona_id = data.terraform_remote_state.infra.outputs.zona_privada_id

  # ARN de cada servicio, con el sufijo /* que exige vpc-lattice-svcs:Invoke.
  arn_invoke = {
    for s in var.servicios : s => "${data.aws_vpclattice_service.svc[s].arn}/*"
  }
}

# Servicios de VPC Lattice, consultados por el nombre que les asigna el controlador.
data "aws_vpclattice_service" "svc" {
  for_each = toset(var.servicios)
  name     = "${each.value}-${var.namespace}"
}

# ------------------------------------------- Política de identidad del rol portal (nivel 3)
# El portal solo puede invocar catalogo y pedidos ({tab:identidades}).
data "aws_iam_policy_document" "portal" {
  statement {
    sid       = "InvocarSoloDependencias"
    effect    = "Allow"
    actions   = ["vpc-lattice-svcs:Invoke"]
    resources = [local.arn_invoke["catalogo"], local.arn_invoke["pedidos"]]
  }
}

resource "aws_iam_role_policy" "portal" {
  name   = "invocar-dependencias"
  role   = local.roles["portal"]
  policy = data.aws_iam_policy_document.portal.json
}

# ------------------------------------------- Política de identidad del rol pedidos (c3a)
# Pedidos solo puede invocar catalogo y clientes (la ruta a pagos usa mTLS, no IAM).
data "aws_iam_policy_document" "pedidos" {
  statement {
    sid       = "InvocarSoloDependencias"
    effect    = "Allow"
    actions   = ["vpc-lattice-svcs:Invoke"]
    resources = [local.arn_invoke["catalogo"], local.arn_invoke["clientes"]]
  }
}

resource "aws_iam_role_policy" "pedidos" {
  name   = "invocar-dependencias"
  role   = local.roles["pedidos"]
  policy = data.aws_iam_policy_document.pedidos.json
}

# ------------------------------------------- Dominio personalizado del modo B (pagos)
# CNAME pagos.<zona> -> dominio que VPC Lattice asigna al servicio de pagos
# (anotación application-networking.k8s.aws/lattice-assigned-domain-name).
resource "aws_route53_record" "pagos" {
  zone_id = local.zona_id
  name    = "pagos.${data.terraform_remote_state.infra.outputs.zona_privada_nombre}"
  type    = "CNAME"
  ttl     = 60
  records = [data.aws_vpclattice_service.svc["pagos"].dns_entry[0].domain_name]
}
