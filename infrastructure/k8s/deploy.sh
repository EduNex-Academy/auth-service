#!/bin/bash

# Auth Service Deployment Script for Existing Keycloak AKS Setup
# This script deploys the auth service to work with your existing Keycloak deployment

set -e

echo "🚀 Deploying Auth Service to your existing Keycloak AKS cluster..."

# Configuration
AUTH_NAMESPACE="auth-service"
KEYCLOAK_NAMESPACE="keycloak"
PROJECT_NAME="auth-service"
IMAGE_TAG="latest"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

print_status() { echo -e "${GREEN}✅ $1${NC}"; }
print_warning() { echo -e "${YELLOW}⚠️  $1${NC}"; }
print_info() { echo -e "${BLUE}ℹ️  $1${NC}"; }
print_error() { echo -e "${RED}❌ $1${NC}"; }

# Check if kubectl is configured and connected to your cluster
check_cluster_connection() {
    print_info "Checking connection to your AKS cluster..."

    if ! kubectl cluster-info &> /dev/null; then
        print_error "kubectl is not configured or cluster is not accessible"
        print_info "Please connect to your existing cluster:"
        echo "  az aks get-credentials --resource-group rg-keycloak-dev --name aks-keycloak-dev"
        exit 1
    fi

    # Check if Keycloak namespace exists (confirming this is your cluster)
    if ! kubectl get namespace $KEYCLOAK_NAMESPACE &> /dev/null; then
        print_error "Keycloak namespace not found. Are you connected to the right cluster?"
        print_info "Expected to find namespace: $KEYCLOAK_NAMESPACE"
        exit 1
    fi

    print_status "Connected to cluster with existing Keycloak deployment"
}

# Get ACR details from your existing infrastructure
get_acr_details() {
    print_info "Getting ACR details from your existing infrastructure..."

    # Try to get ACR from Terraform outputs in the new infrastructure/terraform folder
    if [ -f "../terraform/terraform.tfstate" ]; then
        cd ../terraform
        AUTH_ACR_SERVER=$(terraform output -raw auth_service_acr_login_server 2>/dev/null || echo "")
        cd - > /dev/null

        if [ -n "$AUTH_ACR_SERVER" ]; then
            print_status "Found Auth Service ACR: $AUTH_ACR_SERVER"
            return 0
        fi
    fi

    # Try to find ACR in the resource group
    print_info "Looking for ACR in resource group rg-keycloak-dev..."
    AUTH_ACR_SERVER=$(az acr list --resource-group "rg-keycloak-dev" --query "[?contains(name, 'auth')].loginServer" -o tsv 2>/dev/null | head -1 || echo "")

    if [ -n "$AUTH_ACR_SERVER" ]; then
        print_status "Found ACR server: $AUTH_ACR_SERVER"
    else
        print_warning "Auth Service ACR not found. You may need to deploy the updated Terraform first."
        print_info "To add ACR for auth service, run:"
        echo "  cd ../terraform && terraform apply"
        AUTH_ACR_SERVER="placeholder-acr.azurecr.io"
    fi
}

# Build and push Docker image
build_and_push() {
    if [ "$AUTH_ACR_SERVER" = "akskeycloakdevauthacrakskeycloakdevauth.azurecr.io" ] || [ -z "$AUTH_ACR_SERVER" ]; then
        print_warning "ACR server not properly configured. Skipping image build."
        print_info "Please update the image reference in deployment.yaml manually"
        return 0
    fi

    print_info "Building and pushing Docker image..."

    # Build image from the root directory
    print_info "Building Docker image..."
    cd ../../../
    docker build -t $PROJECT_NAME:$IMAGE_TAG . || {
        print_error "Docker build failed"
        exit 1
    }
    cd - > /dev/null

    # Login to ACR
    ACR_NAME="${AUTH_ACR_SERVER%%.*}"
    print_info "Logging into ACR: $ACR_NAME"
    az acr login --name "$ACR_NAME" || {
        print_error "ACR login failed"
        exit 1
    }

    # Tag and push
    FULL_IMAGE_NAME="$AUTH_ACR_SERVER/$PROJECT_NAME:$IMAGE_TAG"
    print_info "Tagging and pushing image: $FULL_IMAGE_NAME"
    docker tag $PROJECT_NAME:$IMAGE_TAG $FULL_IMAGE_NAME
    docker push $FULL_IMAGE_NAME || {
        print_error "Docker push failed"
        exit 1
    }

    # Update deployment with correct image
    print_info "Updating deployment with correct image reference..."
    sed -i "s|akskeycloakdevauthacrakskeycloakdevauth.azurecr.io/auth-service:latest|$FULL_IMAGE_NAME|g" auth-service/deployment.yaml

    print_status "Image built and pushed successfully"
}

# Deploy auth service to Kubernetes
deploy_auth_service() {
    print_info "Deploying Auth Service to Kubernetes..."

    # Create auth service namespace
    print_info "Creating auth service namespace..."
    kubectl apply -f auth-service/namespace.yaml

    # Apply secrets
    print_info "Applying auth service secrets..."
    kubectl apply -f auth-service/secrets.yaml

    # Deploy auth service
    print_info "Deploying auth service..."
    kubectl apply -f auth-service/deployment.yaml

    # Wait for auth service to be ready
    print_info "Waiting for auth service to be ready..."
    kubectl wait --for=condition=ready pod -l app=auth-service -n $AUTH_NAMESPACE --timeout=300s

    print_status "Auth service deployed successfully"
}

# Check deployment status
check_status() {
    print_info "Checking deployment status..."

    echo ""
    print_info "🔍 Keycloak Status (existing):"
    kubectl get pods -n $KEYCLOAK_NAMESPACE
    echo ""
    print_info "🆕 Auth Service Status (new):"
    kubectl get pods -n $AUTH_NAMESPACE
    echo ""
    print_info "📡 Services:"
    kubectl get services -n $KEYCLOAK_NAMESPACE
    kubectl get services -n $AUTH_NAMESPACE
}

# Show access information
show_access_info() {
    echo ""
    print_status "🎉 Auth Service deployment completed!"
    echo ""
    print_info "🌐 Access your services:"
    echo ""
    echo "1. Port forwarding (for testing):"
    echo "   # Existing Keycloak:"
    echo "   kubectl port-forward service/keycloak-service 8080:8080 -n $KEYCLOAK_NAMESPACE"
    echo ""
    echo "   # New Auth Service:"
    echo "   kubectl port-forward service/auth-service 8081:8081 -n $AUTH_NAMESPACE"
    echo ""
    echo "2. URLs after port forwarding:"
    echo "   Keycloak Admin: http://localhost:8080/admin (admin/admin)"
    echo "   Auth Service API: http://localhost:8081/swagger-ui.html"
    echo ""
    print_info "📝 Next Steps:"
    echo "1. Configure Keycloak client for your auth service:"
    echo "   - Access Keycloak admin console"
    echo "   - Create a new client: 'auth-service-client'"
    echo "   - Set client type to 'confidential'"
    echo "   - Add redirect URIs: http://auth-service.local/*"
    echo "   - Copy the client secret"
    echo ""
    echo "2. Update auth service secrets with Keycloak client details:"
    echo "   kubectl edit secret auth-service-secret -n $AUTH_NAMESPACE"
    echo ""
    echo "3. Restart auth service to pick up new configuration:"
    echo "   kubectl rollout restart deployment/auth-service -n $AUTH_NAMESPACE"
    echo ""
    print_info "🔍 Monitoring commands:"
    echo "   kubectl logs -f deployment/auth-service -n $AUTH_NAMESPACE"
    echo "   kubectl get events -n $AUTH_NAMESPACE --sort-by='.lastTimestamp'"
}

# Cleanup function
cleanup() {
    print_info "🧹 Cleaning up auth service deployment..."
    kubectl delete namespace $AUTH_NAMESPACE --ignore-not-found=true
    print_status "Auth service cleanup completed (Keycloak remains intact)"
}

# Main execution
main() {
    case "${1:-deploy}" in
        "deploy")
            check_cluster_connection
            get_acr_details
            build_and_push
            deploy_auth_service
            check_status
            show_access_info
            ;;
        "build")
            get_acr_details
            build_and_push
            ;;
        "k8s")
            check_cluster_connection
            deploy_auth_service
            check_status
            ;;
        "status")
            check_status
            ;;
        "cleanup")
            cleanup
            ;;
        *)
            echo "Usage: $0 [deploy|build|k8s|status|cleanup]"
            echo "  deploy  - Full deployment (build + push + k8s)"
            echo "  build   - Build and push Docker image only"
            echo "  k8s     - Deploy to Kubernetes only"
            echo "  status  - Check deployment status"
            echo "  cleanup - Remove auth service only (keeps Keycloak)"
            exit 1
            ;;
    esac
}

main "$@"
