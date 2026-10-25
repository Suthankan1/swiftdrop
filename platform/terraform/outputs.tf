output "hosting_target" {
  value       = "local-k3d-k3s"
  description = "Authorized hosting topology per ADR-0011."
}

output "azure_deployment_authorized" {
  value       = var.enable_azure_deployment
  description = "Indicates whether Azure cloud deployment is authorized (must be false for strict $0 baseline)."
}
