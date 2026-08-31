# Deployment & Containerization Architecture — Syed API QA Agent

## 1. Overview

Syed API QA Agent is packaged as a lightweight, production-ready containerized service stack using Docker and Docker Compose. 

The architecture consists of three container services:
1. **`db`**: PostgreSQL 16 relational database for test runs, evidence, and configuration persistence.
2. **`backend`**: Java 21 Spring Boot modular monolith handling discovery, execution, planning, and reporting.
3. **`frontend`**: Next.js 14+ UI providing the dashboard, real-time SSE execution stream, and interactive reports.

---

## 2. Docker Compose Architecture

```yaml
version: '3.8'

services:
  db:
    image: postgres:16-alpine
    container_name: syed-apiqa-db
    restart: unless-stopped
    environment:
      POSTGRES_DB: syed_apiqa
      POSTGRES_USER: apiqa_user
      POSTGRES_PASSWORD: apiqa_password
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U apiqa_user -d syed_apiqa"]
      interval: 5s
      timeout: 5s
      retries: 5

  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: syed-apiqa-backend
    restart: unless-stopped
    depends_on:
      db:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/syed_apiqa
      SPRING_DATASOURCE_USERNAME: apiqa_user
      SPRING_DATASOURCE_PASSWORD: apiqa_password
      SERVER_PORT: 8080
    ports:
      - "8080:8080"

  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile
    container_name: syed-apiqa-frontend
    restart: unless-stopped
    depends_on:
      - backend
    environment:
      NEXT_PUBLIC_API_URL: http://localhost:8080
    ports:
      - "3000:3000"

volumes:
  pgdata:
```

---

## 3. Environment Variables Reference

| Variable Name | Default Value | Description |
| :--- | :--- | :--- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/syed_apiqa` | PostgreSQL JDBC connection URL |
| `SPRING_DATASOURCE_USERNAME` | `apiqa_user` | Database user credentials |
| `SPRING_DATASOURCE_PASSWORD` | `apiqa_password` | Database password |
| `SERVER_PORT` | `8080` | Backend listening port |
| `SYED_SAFETY_SSRF_ENABLED` | `true` | Enables blocking of localhost and private IP addresses |
| `SYED_SAFETY_PROD_DELETE_ENABLED` | `false` | Disables HTTP DELETE in production environments by default |
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | Backend API base URL consumed by Next.js client |
