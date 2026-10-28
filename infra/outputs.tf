# Salidas que consumen las etapas siguientes (plataforma/, k8s/ y politicas/).
# Los nombres entre comentarios son los marcadores que sustituye envsubst en los
# manifiestos (sección 7.2.5 y Anexo B).

output "region" {
  description = "Región del laboratorio."
  value       = var.region
}

output "cuenta" {
  description = "Identificador de la cuenta de AWS (marcador <CUENTA>)."
  value       = data.aws_caller_identity.actual.account_id
}

output "nombre_cluster" {
  description = "Nombre del clúster de EKS (para aws eks update-kubeconfig)."
  value       = module.eks.cluster_name
}

output "id_vpc" {
  description = "Identificador de la VPC (marcador <ID_VPC>)."
  value       = module.vpc.vpc_id
}

output "sg_asociacion" {
  description = "Grupo de seguridad de la asociación con VPC Lattice (marcador <SG_ASOCIACION>)."
  value       = aws_security_group.asociacion_lattice.id
}

output "zona_privada_id" {
  description = "Identificador de la zona privada de Route 53 (para el CNAME de pagos)."
  value       = aws_route53_zone.interna.zone_id
}

output "prefijo_lattice_id" {
  description = "Lista de prefijos administrada de VPC Lattice."
  value       = data.aws_ec2_managed_prefix_list.lattice.id
}

output "grupo_registros_cluster" {
  description = "Grupo de registros del plano de control y de las políticas de red."
  value       = local.grupo_cluster
}

output "roles_carga" {
  description = "ARN de los roles de identidad de carga de trabajo."
  value       = { for k, r in aws_iam_role.carga : k => r.arn }
}

output "nombres_roles_carga" {
  description = "Nombre de los roles de identidad (para adjuntar políticas en politicas/)."
  value       = { for k, r in aws_iam_role.carga : k => r.name }
}

output "zona_privada_nombre" {
  description = "Nombre de la zona privada de Route 53."
  value       = aws_route53_zone.interna.name
}
