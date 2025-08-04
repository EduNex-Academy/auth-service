# Auth Service Infrastructure Addition
# This extends your existing Keycloak infrastructure to include auth service

# Additional Container Registry for Auth Service (if needed)
resource "azurerm_container_registry" "auth_service_acr" {
  name                = "${replace(var.cluster_name, "-", "")}authacr"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  sku                 = "Basic"  # Keep costs low for student plan
  admin_enabled       = true     # Enable for easy authentication

  tags = {
    Environment = var.environment
    Purpose     = "Auth-Service-Images"
  }
}

# Grant AKS access to the auth service ACR
resource "azurerm_role_assignment" "aks_auth_acr_pull" {
  principal_id                     = azurerm_kubernetes_cluster.aks.kubelet_identity[0].object_id
  role_definition_name             = "AcrPull"
  scope                           = azurerm_container_registry.auth_service_acr.id
  skip_service_principal_aad_check = true
}
