variable "enable_azure_deployment" {
  type        = bool
  default     = false
  description = "Strict $0 hosting invariant: Azure deployment is disabled by default. Gated by ADR-0011 and Issue #172."
}
