# Nagorik Seba

Nagorik Seba is a ward-level civic complaint platform for Bangladesh. Residents will be able to report local infrastructure problems, track each status change, and rate the resolution. Authorities will be able to assign, resolve, and monitor complaints transparently.

## Current foundation

- Spring Boot 3.5 / Java 21 / Maven Wrapper
- Spring Security with JWT-based authentication
- Citizen registration and login APIs
- JPA entities and repositories for users, wards, departments, complaints, status updates, attachments, SLA rules, notifications, and ward performance
- H2 for a no-setup local start; PostgreSQL profile for deployment
- Thymeleaf public landing page

The complaint workflow, file uploads, dashboards, maps, notifications, and design-pattern services are the next implementation phases.

## Run locally

Java 21 is required. Maven is included through the wrapper, so a global Maven installation is not needed.

```bash
./mvnw spring-boot:run
```

Open `http://localhost:8080`. The development database is an in-memory H2 database, so its data resets when the application stops.

## Authentication API

Register a citizen:

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
    "fullName":"Amina Rahman",
    "email":"amina@example.com",
    "phone":"01712345678",
    "password":"a-secure-password"
  }'
```

Log in with an email or Bangladeshi phone number:

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"identifier":"amina@example.com","password":"a-secure-password"}'
```

Both endpoints return a Bearer access token. Send it as `Authorization: Bearer <token>` to protected APIs as they are added.

## PostgreSQL

Set these variables and enable the PostgreSQL profile:

```bash
export SPRING_PROFILES_ACTIVE=postgres
export DB_URL='jdbc:postgresql://localhost:5432/nagorik_seba'
export DB_USERNAME='postgres'
export DB_PASSWORD='your-password'
export JWT_SECRET='a-base64-encoded-secret-of-at-least-32-bytes'
./mvnw spring-boot:run
```

The `postgres` profile creates or updates the development schema. Before using a shared database, add versioned Flyway or Liquibase migrations and change `ddl-auto` to `validate`.

## Verify

```bash
./mvnw test
```
