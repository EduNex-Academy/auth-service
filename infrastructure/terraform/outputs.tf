output "kube_config" {
  description = "Raw Kubernetes config to be used by kubectl and other compatible tools"
  value       = azurerm_kubernetes_cluster.aks.kube_config_raw
  sensitive   = true
}

output "cluster_name" {
  description = "Name of the AKS cluster"
  value       = azurerm_kubernetes_cluster.aks.name
}

output "cluster_fqdn" {
  description = "FQDN of the AKS cluster"
  value       = azurerm_kubernetes_cluster.aks.fqdn
}

output "cluster_identity" {
  description = "System assigned identity of the AKS cluster"
  value = {
    principal_id = azurerm_kubernetes_cluster.aks.identity[0].principal_id
    tenant_id    = azurerm_kubernetes_cluster.aks.identity[0].tenant_id
  }
}

output "acr_login_server" {
  description = "Login server for the Azure Container Registry"
  value       = azurerm_container_registry.acr.login_server
}

output "resource_group_name" {
  description = "Name of the resource group"
  value       = azurerm_resource_group.rg.name
}

output "log_analytics_workspace_id" {
  description = "ID of the Log Analytics workspace"
  value       = azurerm_log_analytics_workspace.aks.id
}

# Auth Service specific outputs
output "auth_service_acr_login_server" {
  description = "Login server for the Auth Service Azure Container Registry"
  value       = azurerm_container_registry.auth_service_acr.login_server
}

output "auth_service_acr_admin_username" {
  description = "Admin username for the Auth Service Azure Container Registry"
  value       = azurerm_container_registry.auth_service_acr.admin_username
  sensitive   = true
}

output "auth_service_acr_admin_password" {
  description = "Admin password for the Auth Service Azure Container Registry"
  value       = azurerm_container_registry.auth_service_acr.admin_password
  sensitive   = true
}

output "deployment_commands" {
  description = "Commands to connect to the cluster and deploy auth service"
  value = [
    "# Connect to your existing AKS cluster:",
    "az aks get-credentials --resource-group ${azurerm_resource_group.rg.name} --name ${azurerm_kubernetes_cluster.aks.name}",
    "",
    "# Build and push auth service image:",
    "docker build -t auth-service:latest .",
    "docker tag auth-service:latest ${azurerm_container_registry.auth_service_acr.login_server}/auth-service:latest",
    "az acr login --name ${azurerm_container_registry.auth_service_acr.name}",
    "docker push ${azurerm_container_registry.auth_service_acr.login_server}/auth-service:latest",
    "",
    "# Deploy auth service:",
    "cd deployment/k8s && ./deploy.sh"
  ]
}
