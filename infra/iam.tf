# Identidades de carga de trabajo (sección 7.2.4, {tab:identidades}).
# Cada cuenta de servicio tiene un rol de IAM propio, asociado con EKS Pod Identity.
# Las credenciales temporales que recibe el pod llevan etiquetas de sesión (clúster,
# espacio de nombres y cuenta de servicio) que VPC Lattice usa como identidad.
#
# Las políticas de identidad de portal y pedidos (nivel 3) necesitan los ARN de los
# servicios de VPC Lattice, que solo existen tras desplegar las rutas; por eso se
# declaran aparte, en politicas/ (problema de dependencia circular, sección 7.2.10).

locals {
  # Identidades: nombre del rol => (espacio de nombres, cuenta de servicio).
  identidades = {
    portal    = { ns = "tienda", sa = "portal" }
    pedidos   = { ns = "tienda", sa = "pedidos" }
    catalogo  = { ns = "tienda", sa = "catalogo" }
    clientes  = { ns = "tienda", sa = "clientes" }
    pagos     = { ns = "tienda", sa = "pagos" }
    auditoria = { ns = "auditoria", sa = "auditoria" }
  }
}

# Política de confianza común: solo el servicio de EKS Pod Identity puede asumir el
# rol, y únicamente para la terna (clúster, espacio de nombres, cuenta de servicio)
# que corresponde a esa identidad ({lst:c_confianza}).
data "aws_iam_policy_document" "confianza_pod_identity" {
  for_each = local.identidades

  statement {
    sid     = "AllowEksAuthToAssumeRoleForPodIdentity"
    effect  = "Allow"
    actions = ["sts:AssumeRole", "sts:TagSession"]

    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/eks-cluster-name"
      values   = [var.nombre]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/kubernetes-namespace"
      values   = [each.value.ns]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/kubernetes-service-account"
      values   = [each.value.sa]
    }
  }
}

resource "aws_iam_role" "carga" {
  for_each = local.identidades

  name                 = "${var.nombre}-${each.key}"
  assume_role_policy   = data.aws_iam_policy_document.confianza_pod_identity[each.key].json
  max_session_duration = 3600
}

resource "aws_eks_pod_identity_association" "carga" {
  for_each = local.identidades

  cluster_name    = module.eks.cluster_name
  namespace       = each.value.ns
  service_account = each.value.sa
  role_arn        = aws_iam_role.carga[each.key].arn
}

# ------------------------------------------- Política de identidad del auditor (c3b)
# Solo lectura de los tres grupos de registros del laboratorio (mínimo privilegio).
data "aws_iam_policy_document" "auditoria" {
  statement {
    sid     = "LecturaDeRegistrosDelLaboratorio"
    effect  = "Allow"
    actions = ["logs:StartLiveTail", "logs:FilterLogEvents"]
    resources = [
      "arn:aws:logs:${var.region}:${data.aws_caller_identity.actual.account_id}:log-group:/${var.nombre}/*",
      "arn:aws:logs:${var.region}:${data.aws_caller_identity.actual.account_id}:log-group:${local.grupo_cluster}*"
    ]
  }

  statement {
    sid       = "DescubrimientoDeGrupos"
    effect    = "Allow"
    actions   = ["logs:DescribeLogGroups"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "auditoria" {
  name   = "lectura-registros"
  role   = aws_iam_role.carga["auditoria"].id
  policy = data.aws_iam_policy_document.auditoria.json
}

# ------------------------------------------- Rol del AWS Gateway API Controller (b1)
# Usa la política recomendada del proyecto (vpc-lattice:*, ec2 Describe*, entrega de
# registros, etiquetas, ACM y creación de roles vinculados a servicios).
data "aws_iam_policy_document" "confianza_controlador" {
  statement {
    sid     = "AllowEksAuthToAssumeRoleForPodIdentity"
    effect  = "Allow"
    actions = ["sts:AssumeRole", "sts:TagSession"]

    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/eks-cluster-name"
      values   = [var.nombre]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/kubernetes-namespace"
      values   = ["aws-application-networking-system"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/kubernetes-service-account"
      values   = ["gateway-api-controller"]
    }
  }
}

resource "aws_iam_role" "controlador" {
  name                 = "${var.nombre}-controlador"
  assume_role_policy   = data.aws_iam_policy_document.confianza_controlador.json
  max_session_duration = 3600
}

resource "aws_iam_role_policy" "controlador" {
  name   = "controlador"
  role   = aws_iam_role.controlador.id
  policy = file("${path.module}/politica-controlador.json")
}

resource "aws_eks_pod_identity_association" "controlador" {
  cluster_name    = module.eks.cluster_name
  namespace       = "aws-application-networking-system"
  service_account = "gateway-api-controller"
  role_arn        = aws_iam_role.controlador.arn
}

data "aws_caller_identity" "actual" {}
