#!/bin/bash

# Keycloak AKS Deployment Script
# This script deploys the AKS infrastructure and Keycloak application

set -e

echo "🚀 Starting Keycloak AKS Deployment..."

# Step 1: Deploy AKS Infrastructure with Terraform
echo "📦 Deploying AKS infrastructure..."
terraform init
terraform plan
terraform apply -auto-approve

# Step 2: Get AKS credentials
echo "🔑 Configuring kubectl..."
CLUSTER_NAME=$(terraform output -raw cluster_name)
RESOURCE_GROUP=$(terraform output -raw resource_group_name)
az aks get-credentials --resource-group $RESOURCE_GROUP --name $CLUSTER_NAME --overwrite-existing

# Step 3: Deploy Keycloak to AKS
echo "🔐 Deploying Keycloak to AKS..."
kubectl apply -f keycloak-namespace.yaml
kubectl apply -f keycloak-secrets.yaml
kubectl apply -f postgres-deployment.yaml

# Wait for PostgreSQL to be ready
echo "⏳ Waiting for PostgreSQL to be ready..."
kubectl wait --for=condition=ready pod -l app=postgres -n keycloak --timeout=300s

# Deploy Keycloak
kubectl apply -f keycloak-deployment.yaml

# Wait for Keycloak to be ready
echo "⏳ Waiting for Keycloak to be ready..."
kubectl wait --for=condition=ready pod -l app=keycloak -n keycloak --timeout=600s

# Get service information
echo "✅ Deployment complete!"
echo "📊 Cluster Information:"
kubectl get nodes
echo ""
echo "🔐 Keycloak Services:"
kubectl get svc -n keycloak
echo ""
echo "📦 Keycloak Pods:"
kubectl get pods -n keycloak

echo ""
echo "🌐 To access Keycloak:"
echo "1. Port forward: kubectl port-forward svc/keycloak-service 8080:8080 -n keycloak"
echo "2. Access: http://localhost:8080"
echo "3. Admin credentials: admin/admin (change these in production!)"
