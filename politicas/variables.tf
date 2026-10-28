variable "region" {
  description = "Región de AWS; debe coincidir con la de la etapa infra."
  type        = string
  default     = "us-east-1"
}

variable "namespace" {
  description = "Espacio de nombres de la aplicación (parte del nombre del servicio Lattice)."
  type        = string
  default     = "tienda"
}

# El AWS Gateway API Controller nombra cada servicio de VPC Lattice como
# <ruta (máx. 20)>-<namespace (máx. 18)> (utils.LatticeServiceName). Con el namespace
# "tienda" y las rutas catalogo/clientes/pedidos/pagos resultan los nombres de abajo.
variable "servicios" {
  description = "Nombres de ruta cuyos servicios de Lattice se consultan por nombre."
  type        = list(string)
  default     = ["catalogo", "clientes", "pedidos", "pagos"]
}
