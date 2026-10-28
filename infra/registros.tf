# Telemetría centralizada en CloudWatch Logs (sección 7.2.7 y {tab:instrumentos}).
# Tres grupos alimentan la capa de detección:
#   /zt-lab/lattice/acceso   registros de acceso de VPC Lattice (AccessLogPolicy, k8s/)
#   /zt-lab/vpc/flujos       VPC Flow Logs con formato personalizado
#   /aws/eks/zt-lab/cluster  decisiones de políticas de red (lo crea el módulo de EKS)

resource "aws_cloudwatch_log_group" "acceso" {
  name              = "/${var.nombre}/lattice/acceso"
  retention_in_days = var.retencion_registros_dias
}

resource "aws_cloudwatch_log_group" "flujos" {
  name              = "/${var.nombre}/vpc/flujos"
  retention_in_days = var.retencion_registros_dias
}

# ----------------------------------------------------------------- VPC Flow Logs
resource "aws_flow_log" "vpc" {
  vpc_id                   = module.vpc.vpc_id
  traffic_type             = "ALL"
  log_destination_type     = "cloud-watch-logs"
  log_destination          = aws_cloudwatch_log_group.flujos.arn
  iam_role_arn             = aws_iam_role.flujos.arn
  max_aggregation_interval = 60

  # Incluye las direcciones originales del paquete (pkt-srcaddr/pkt-dstaddr) para
  # resolver el origen real tras la traducción de la NAT (sección 7.3.4, RF2).
  log_format = join(" ", [
    "$${version}", "$${interface-id}", "$${srcaddr}", "$${dstaddr}",
    "$${srcport}", "$${dstport}", "$${protocol}", "$${packets}", "$${bytes}",
    "$${start}", "$${end}", "$${action}", "$${pkt-srcaddr}", "$${pkt-dstaddr}"
  ])
}

resource "aws_iam_role" "flujos" {
  name = "${var.nombre}-flow-logs"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "vpc-flow-logs.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy" "flujos" {
  name = "entrega-registros"
  role = aws_iam_role.flujos.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Resource = "*"
      Action = [
        "logs:CreateLogStream", "logs:PutLogEvents",
        "logs:DescribeLogGroups", "logs:DescribeLogStreams"
      ]
    }]
  })
}

# ------------------------------------- Permiso del rol del nodo (agente VPC CNI)
# Registra las decisiones de las políticas de red en /aws/eks/zt-lab/cluster.
resource "aws_iam_policy" "registros_red" {
  name = "${var.nombre}-registros-politicas-red"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "logs:DescribeLogGroups", "logs:CreateLogGroup",
        "logs:CreateLogStream", "logs:PutLogEvents"
      ]
      Resource = "*"
    }]
  })
}
