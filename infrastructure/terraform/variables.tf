variable "location" {
  description = "The Azure region where resources will be created"
  type        = string
  default     = "East Asia"
}

variable "resource_group_name" {
  description = "Name of the resource group"
  type        = string
  default     = "rg-keycloak-dev"
}

variable "cluster_name" {
  description = "Name of the AKS cluster"
  type        = string
  default     = "aks-keycloak-dev"
}

variable "node_count" {
  description = "Number of nodes in the default node pool"
  type        = number
  default     = 1
}

variable "vm_size" {
  description = "Size of the virtual machines"
  type        = string
  default     = "Standard_B2s"
}

variable "kubernetes_version" {
  description = "Kubernetes version"
  type        = string
  default     = "1.32.6"
}

variable "environment" {
  description = "Environment name (dev, staging, prod)"
  type        = string
  default     = "dev"
}
