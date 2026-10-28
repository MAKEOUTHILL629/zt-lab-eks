# Variables de la infraestructura. Los valores por defecto corresponden a los de la
# monografía (sección 7.2.3, {tab:direccionamiento}); se dejan parametrizados para que
# el laboratorio pueda recrearse en otra región o con otro direccionamiento sin editar
# los recursos.

variable "region" {
  description = "Región de AWS. La monografía usa us-east-1 por disponibilidad de VPC Lattice."
  type        = string
  default     = "us-east-1"
}

variable "nombre" {
  description = "Nombre base del laboratorio; prefijo del clúster, la VPC y los roles."
  type        = string
  default     = "zt-lab"
}

variable "version_kubernetes" {
  description = "Versión de Kubernetes del plano de control de Amazon EKS."
  type        = string
  default     = "1.36"
}

variable "cidr_vpc" {
  description = "Bloque CIDR de la VPC del laboratorio."
  type        = string
  default     = "10.0.0.0/16"
}

variable "zonas" {
  description = "Zonas de disponibilidad (dos, como exige Amazon EKS)."
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b"]
}

variable "subredes_publicas" {
  description = "CIDR de las subredes públicas; la AZ a aloja la única NAT Gateway."
  type        = list(string)
  default     = ["10.0.0.0/24", "10.0.1.0/24"]
}

variable "subredes_privadas" {
  description = "CIDR de las subredes privadas; alojan los nodos y los pods."
  type        = list(string)
  default     = ["10.0.16.0/20", "10.0.32.0/20"]
}

variable "tipo_instancia" {
  description = "Tipo de instancia de los nodos administrados."
  type        = string
  default     = "t3.large"
}

variable "retencion_registros_dias" {
  description = "Días de retención de los grupos de registros de CloudWatch Logs."
  type        = number
  default     = 7
}

variable "zona_privada" {
  description = "Zona DNS privada para el dominio personalizado del modo B (pagos)."
  type        = string
  default     = "zt-lab.internal"
}

variable "limite_presupuesto_usd" {
  description = "Umbral de la alerta de AWS Budgets, en dólares (sección 6.6.2)."
  type        = number
  default     = 90
}

variable "correo_presupuesto" {
  description = "Correo para las notificaciones de AWS Budgets; vacío desactiva la alerta."
  type        = string
  default     = ""
}
