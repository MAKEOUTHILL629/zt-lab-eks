# VPC y clúster de Amazon EKS del laboratorio (sección 7.2.3 y Anexo F de la monografía).
# Los módulos se referencian por su etiqueta de versión en Git para que la validación
# (tofu validate) sea reproducible sin un registro de módulos.

locals {
  # Grupo de registros del plano de control y de las decisiones de políticas de red,
  # creado por el módulo de EKS. El agente de Amazon VPC CNI escribe aquí cuando
  # enableCloudWatchLogs = "true".
  grupo_cluster = "/aws/eks/${var.nombre}/cluster"
}

module "vpc" {
  source = "git::https://github.com/terraform-aws-modules/terraform-aws-vpc.git?ref=v6.7.3"

  name = var.nombre
  cidr = var.cidr_vpc

  azs             = var.zonas
  public_subnets  = var.subredes_publicas
  private_subnets = var.subredes_privadas

  enable_nat_gateway   = true
  single_nat_gateway   = true # una sola NAT: menor costo, sin redundancia de salida
  enable_dns_hostnames = true
}

module "eks" {
  source = "git::https://github.com/terraform-aws-modules/terraform-aws-eks.git?ref=v21.29.0"

  name               = var.nombre
  kubernetes_version = var.version_kubernetes

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  endpoint_public_access                   = true
  enable_cluster_creator_admin_permissions = true

  # Retención corta del grupo /aws/eks/zt-lab/cluster (laboratorio efímero).
  cloudwatch_log_group_retention_in_days = var.retencion_registros_dias

  addons = {
    coredns                = {}
    kube-proxy             = {}
    eks-pod-identity-agent = { before_compute = true }
    vpc-cni = {
      before_compute = true
      # Políticas de red en modo estricto con registro de decisiones hacia
      # CloudWatch Logs (sección 7.2.7). El modo estricto impone denegación por
      # defecto a nivel del CNI; las políticas explícitas se aplican en k8s/.
      configuration_values = jsonencode({
        enableNetworkPolicy = "true"
        nodeAgent = {
          enablePolicyEventLogs = "true"
          enableCloudWatchLogs  = "true"
        }
        env = {
          NETWORK_POLICY_ENFORCING_MODE = "strict"
        }
      })
    }
  }

  eks_managed_node_groups = {
    laboratorio = {
      instance_types = [var.tipo_instancia]
      ami_type       = "AL2023_x86_64_STANDARD"
      min_size       = 2
      max_size       = 2
      desired_size   = 2

      metadata_options = {
        http_endpoint               = "enabled"
        http_tokens                 = "required" # IMDSv2 obligatorio
        http_put_response_hop_limit = 1          # los pods no alcanzan el IMDS (R3)
      }

      # El rol del nodo (que usa el agente de Amazon VPC CNI) necesita escribir los
      # registros de decisiones de las políticas de red.
      iam_role_additional_policies = {
        registros = aws_iam_policy.registros_red.arn
      }
    }
  }

  # El plano de datos de los microservicios solo debe recibir tráfico desde VPC
  # Lattice en el puerto de la aplicación (sección 7.2.3, control de soporte).
  node_security_group_additional_rules = {
    lattice_aplicacion = {
      description     = "Entrada 8443 solo desde la lista de prefijos de VPC Lattice"
      protocol        = "tcp"
      from_port       = 8443
      to_port         = 8443
      type            = "ingress"
      prefix_list_ids = [data.aws_ec2_managed_prefix_list.lattice.id]
    }
  }
}
