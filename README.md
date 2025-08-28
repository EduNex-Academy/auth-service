# Auth Service

This is a Spring Boot authentication service integrated with Keycloak for identity and access management.

## 🐳 Local Development with Docker

### Prerequisites

- Docker Desktop installed and running
- Docker Compose v3.9 or higher
- Git

### Quick Start

1. **Clone the repository and navigate to the auth-service directory**
   ```bash
   git clone <repository-url>
   cd backend/services/auth-service
   ```

2. **Start all services**
   ```bash
   docker-compose up -d
   ```

3. **Wait for services to be ready**
   - Keycloak will be available at: http://localhost:8080
   - Auth Service will be available at: http://localhost:8081

### Service URLs

| Service | URL | Credentials |
|---------|-----|-------------|
| Keycloak Admin Console | http://localhost:8080/admin | admin/admin |
| Auth Service API | http://localhost:8081 | - |
| Auth Service Swagger UI | http://localhost:8081/swagger-ui.html | - |
| PostgreSQL (Keycloak DB) | localhost:5433 | keycloak/keycloak |

### Initial Keycloak Setup

After starting the services, you need to configure Keycloak:

1. **Access Keycloak Admin Console**
   - URL: http://localhost:8080/admin
   - Username: `admin`
   - Password: `admin`

2. **Create a Realm**
   - Click "Create Realm"
   - Name: `edunex-platform`
   - Click "Create"

3. **Create a Client**
   - Go to Clients → Create Client
   - Client ID: `edunex-platform-client`
   - Client type: OpenID Connect
   - Client authentication: On (confidential)
   - Valid redirect URIs: 
     - `http://frontend:5173/callback`
     - `http://frontend:3000/callback`
   - Click "Save"

4. **Get Client Secret**
   - Go to the created client → Credentials tab
   - Copy the client secret
   - Update `docker.env` file with the new secret if needed

### Environment Configuration

The service uses the `docker.env` file for configuration. Key settings:

```env
# Keycloak Configuration
KEYCLOAK_SERVER_URL=http://keycloak:8080
KEYCLOAK_REALM=edunex-platform
KEYCLOAK_CLIENT_ID=edunex-platform-client
KEYCLOAK_CLIENT_SECRET=your-client-secret-here

# Application Server
SERVER_PORT=8081
```

### Development Commands

```bash
# Start all services
docker-compose up -d

# View logs
docker-compose logs -f auth-service
docker-compose logs -f keycloak

# Stop all services
docker-compose down

# Rebuild and start auth-service
docker-compose up -d --build auth-service

# Clean up everything (including volumes)
docker-compose down -v
```

### Testing the Setup

1. **Health Check**
   ```bash
   curl http://localhost:8081/actuator/health
   ```

2. **API Documentation**
   Visit: http://localhost:8081/swagger-ui.html

3. **Test Authentication Endpoints**
   - Registration: `POST /api/auth/register`
   - Login: `POST /api/auth/login`
   - Profile: `GET /api/auth/profile`

### Troubleshooting

**Keycloak not starting:**
- Check if port 8080 is available
- Wait longer for PostgreSQL to initialize (first startup takes time)

**Auth Service connection errors:**
- Ensure Keycloak is fully started before auth-service
- Check the realm and client configuration in Keycloak

**Port conflicts:**
- Change ports in `docker-compose.yml` if needed
- Default ports: 8080 (Keycloak), 8081 (Auth Service), 5433 (PostgreSQL)

### Stopping Services

```bash
# Stop services but keep data
docker-compose down

# Stop and remove all data
docker-compose down -v
```

## 📋 API Documentation

Once the service is running, you can access:
- Swagger UI: http://localhost:8081/swagger-ui.html
- OpenAPI Spec: http://localhost:8081/v3/api-docs

## 🔧 Development

For active development, you can run the auth-service outside Docker while keeping Keycloak in Docker:

1. Start only Keycloak and PostgreSQL:
   ```bash
   docker-compose up -d postgres-keycloak keycloak
   ```

2. Update `src/main/resources/application.properties` to use `localhost:8080` for Keycloak URL

3. Run the Spring Boot application from your IDE or:
   ```bash
   ./gradlew bootRun
   ```
