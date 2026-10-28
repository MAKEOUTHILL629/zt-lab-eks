# Alerta de costo (sección 6.6.2 y riesgo R11). Se crea solo si se indica un correo;
# de lo contrario, el control de gasto queda a cargo de AWS Cost Explorer manual.
resource "aws_budgets_budget" "laboratorio" {
  count = var.correo_presupuesto != "" ? 1 : 0

  name         = "${var.nombre}-mensual"
  budget_type  = "COST"
  limit_amount = tostring(var.limite_presupuesto_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.correo_presupuesto]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.correo_presupuesto]
  }
}
