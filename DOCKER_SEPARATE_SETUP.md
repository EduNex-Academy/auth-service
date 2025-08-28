# Running Auth Service Separately from Keycloak/Database

This setup allows you to run the Auth Service as a standalone container while keeping Keycloak and PostgreSQL in a Docker Compose setup.

## Manual commands

1. **Start Keycloak and Database with Docker Compose:**
   ```cmd
   docker-compose up -d
   ```

2. **Build the Auth Service Docker image:**
   ```cmd
   docker build -t auth-service:latest .
   ```

3. **Run the Auth Service container:**
   ```cmd
   docker run -d --name auth-service --env-file docker.env -p 8081:8081 --network=keycloak_edunex-platform-network auth-service:latest
   ```

## Access URLs

- **Keycloak Admin Console:** http://localhost:8080 (admin/admin)
- **Auth Service:** http://localhost:8081
- **Auth Service API Docs:** http://localhost:8081/swagger-ui.html

## Cleanup Commands

```cmd
# Stop and remove Auth Service container
docker stop auth-service
docker rm auth-service

# Stop Keycloak and Database
docker-compose down

# Optional: Remove volumes (this will delete Keycloak data)
docker-compose down -v
```

## Files Created/Modified

- `docker-compose.yml`: Compose file with only Keycloak and PostgreSQL
- `docker.env`: Environment variables for the standalone Auth Service
- `application.properties`: Updated to remove KEYCLOAK_EXPOSED_URL references

## Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│  Auth Service   │    │    Keycloak      │    │   PostgreSQL    │
│   Container     │◄──►│   Container      │◄──►│   Container     │
│   (Standalone)  │    │  (In Compose)    │    │  (In Compose)   │
└─────────────────┘    └──────────────────┘    └─────────────────┘
```
