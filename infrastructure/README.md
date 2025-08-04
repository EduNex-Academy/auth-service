# Azure Deployment with Terraform

This guide covers deploying the Auth Service to Azure Kubernetes Service (AKS) using Terraform for infrastructure provisioning and Kubernetes manifests for application deployment.

## 🏗️ Architecture Overview

The deployment creates:
- Azure Resource Group
- Azure Kubernetes Service (AKS) cluster
- Azure Container Registry (ACR)
- Keycloak with PostgreSQL database
- Auth Service application

## 📋 Prerequisites

### Required Tools
- [Azure CLI](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli) v2.0+
- [Terraform](https://www.terraform.io/downloads.html) v1.0+
- [kubectl](https://kubernetes.io/docs/tasks/tools/) v1.20+
- [Docker](https://docs.docker.com/get-docker/) (for building images)
- Git

### Azure Setup
1. **Azure Subscription**: Ensure you have an active Azure subscription
2. **Azure CLI Login**:
   ```bash
   az login
   az account set --subscription "<your-subscription-id>"
   ```

3. **Service Principal** (Optional but recommended for CI/CD):
   ```bash
   az ad sp create-for-rbac --name "terraform-sp" --role="Contributor" --scopes="/subscriptions/<subscription-id>"
   ```

## 🚀 Deployment Steps

### Step 1: Infrastructure Provisioning

1. **Navigate to Terraform directory**:
   ```bash
   cd infrastructure/terraform
   ```

2. **Review and customize variables**:
   Edit `terraform.tfvars` or create one:
   ```hcl
   location = "East Asia"
   resource_group_name = "rg-keycloak-dev"
   cluster_name = "aks-keycloak-dev"
   node_count = 2
   vm_size = "Standard_B2s"
   kubernetes_version = "1.32.6"
   environment = "dev"
   ```

3. **Initialize Terraform**:
   ```bash
   terraform init
   ```

4. **Plan deployment**:
   ```bash
   terraform plan
   ```

5. **Apply infrastructure**:
   ```bash
   terraform apply
   ```
   Type `yes` when prompted to confirm.

### Step 2: Connect to AKS Cluster

```bash
az aks get-credentials --resource-group rg-keycloak-dev --name aks-keycloak-dev
```

Verify connection:
```bash
kubectl cluster-info
kubectl get nodes
```

### Step 3: Deploy Applications

1. **Navigate to Kubernetes directory**:
   ```bash
   cd ../k8s
   ```

2. **Make deploy script executable**:
   ```bash
   chmod +x deploy.sh
   ```

3. **Deploy all components**:
   ```bash
   ./deploy.sh
   ```

   Or deploy step by step:
   ```bash
   # Deploy Keycloak first
   kubectl apply -f keycloak/
   
   # Wait for Keycloak to be ready
   kubectl wait --for=condition=ready pod -l app=keycloak -n keycloak --timeout=300s
   
   # Deploy Auth Service
   kubectl apply -f auth-service/
   ```

## 🔧 Configuration

### Environment Variables

Update the secrets in `k8s/auth-service/secrets.yaml` and `k8s/keycloak/secrets.yaml` with your specific values:

**Auth Service Secrets** (`k8s/auth-service/secrets.yaml`):
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: auth-service-secret
  namespace: auth-service
data:
  KEYCLOAK_SERVER_URL: <base64-encoded-url>
  KEYCLOAK_CLIENT_SECRET: <base64-encoded-secret>
  # ... other secrets
```

**Keycloak Secrets** (`k8s/keycloak/secrets.yaml`):
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: keycloak-secret
  namespace: keycloak
data:
  POSTGRES_PASSWORD: <base64-encoded-password>
  KEYCLOAK_ADMIN_PASSWORD: <base64-encoded-password>
```

### Encoding Values for Kubernetes Secrets

```bash
# Encode values to base64
echo -n "your-secret-value" | base64

# Decode for verification
echo "encoded-value" | base64 -d
```

## 🌐 Accessing Services

### Port Forwarding (Development)

**Keycloak Admin Console**:
```bash
kubectl port-forward service/keycloak-service 8080:8080 -n keycloak
```
Access at: http://localhost:8080/admin

**Auth Service API**:
```bash
kubectl port-forward service/auth-service 8081:8081 -n auth-service
```
Access at: http://localhost:8081/swagger-ui.html

### Load Balancer (Production)

For production, configure ingress or load balancer services:

1. **Install NGINX Ingress Controller**:
   ```bash
   kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.8.1/deploy/static/provider/cloud/deploy.yaml
   ```

2. **Create Ingress Resource** (example):
   ```yaml
   apiVersion: networking.k8s.io/v1
   kind: Ingress
   metadata:
     name: auth-service-ingress
     namespace: auth-service
   spec:
     ingressClassName: nginx
     rules:
     - host: auth.yourdomain.com
       http:
         paths:
         - path: /
           pathType: Prefix
           backend:
             service:
               name: auth-service
               port:
                 number: 8081
   ```

## 📊 Monitoring and Troubleshooting

### Check Pod Status
```bash
# Auth Service pods
kubectl get pods -n auth-service
kubectl describe pod <pod-name> -n auth-service

# Keycloak pods
kubectl get pods -n keycloak
kubectl describe pod <pod-name> -n keycloak
```

### View Logs
```bash
# Auth Service logs
kubectl logs -f deployment/auth-service -n auth-service

# Keycloak logs
kubectl logs -f deployment/keycloak -n keycloak

# PostgreSQL logs
kubectl logs -f deployment/postgres -n keycloak
```

### Common Issues and Solutions

**Pods stuck in Pending state**:
- Check node resources: `kubectl describe nodes`
- Check storage class: `kubectl get storageclass`

**ImagePullBackOff errors**:
- Verify ACR authentication: `az acr login --name <registry-name>`
- Check image tags and registry URLs

**Service connection issues**:
- Verify service discovery: `kubectl get svc -A`
- Check network policies and security groups

## 🔄 Updates and Maintenance

### Updating Application Images

1. **Build and push new image**:
   ```bash
   cd ../../  # Back to auth-service root
   docker build -t your-acr.azurecr.io/auth-service:v2.0 .
   docker push your-acr.azurecr.io/auth-service:v2.0
   ```

2. **Update deployment**:
   ```bash
   kubectl set image deployment/auth-service auth-service=your-acr.azurecr.io/auth-service:v2.0 -n auth-service
   ```

### Scaling Applications

```bash
# Scale auth service
kubectl scale deployment auth-service --replicas=3 -n auth-service

# Scale Keycloak
kubectl scale deployment keycloak --replicas=2 -n keycloak
```

### Infrastructure Updates

```bash
cd infrastructure/terraform
terraform plan
terraform apply
```

## 🧹 Cleanup

### Remove Applications
```bash
kubectl delete -f k8s/auth-service/
kubectl delete -f k8s/keycloak/
```

### Destroy Infrastructure
```bash
cd infrastructure/terraform
terraform destroy
```

## 🔐 Security Considerations

1. **Secrets Management**: Use Azure Key Vault for production secrets
2. **Network Security**: Implement network policies and Azure Network Security Groups
3. **RBAC**: Configure Kubernetes Role-Based Access Control
4. **Image Security**: Scan container images for vulnerabilities
5. **TLS**: Enable TLS/SSL for all external communications

## 📈 Production Recommendations

1. **High Availability**: Deploy across multiple availability zones
2. **Monitoring**: Implement Azure Monitor and Application Insights
3. **Backup**: Configure regular backups for PostgreSQL
4. **Autoscaling**: Enable cluster autoscaler and horizontal pod autoscaler
5. **Ingress**: Use Application Gateway or NGINX Ingress with TLS certificates

## 🆘 Support

For issues with:
- **Infrastructure**: Check Terraform state and Azure resources
- **Kubernetes**: Use `kubectl` commands for debugging
- **Applications**: Check application logs and configurations

Remember to check the main [README.md](../README.md) for local development setup and API documentation.
