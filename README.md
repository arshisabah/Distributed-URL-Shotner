# 🔗 Production-Grade URL Shortener

A fully functional, distributed URL shortener built with **Java 21 + Spring Boot 3.2** —
covering every system design concept from the 7-part series.

---

## Architecture Overview

```
Internet → API Gateway (port 80)
             ├── /api/v1/auth/**     → Auth Service     (port 8081)
             ├── /api/v1/urls/**     → URL Service      (port 8080)
             ├── /api/v1/analytics/**→ Analytics Service(port 8082)
             └── /{shortCode}       → URL Service      (port 8080)

URL Service dependencies:
  PostgreSQL 15  — durable URL storage (primary + read replica)
  Redis 7        — L1 cache (24 h TTL), rate limiting (token bucket)
  Kafka          — async click events (url.clicked topic)

Analytics Service dependencies:
  Kafka          — consumes url.clicked → increments Redis counters
  Redis          — stores click counts per shortCode
```

---

## Prerequisites

| Tool          | Version   | Install                                |
|---------------|-----------|----------------------------------------|
| Java          | 21+       | `sdk install java 21-tem`              |
| Maven         | 3.9+      | `brew install maven`                   |
| Docker        | 24+       | https://docs.docker.com/get-docker/    |
| Docker Compose| 2.20+     | Bundled with Docker Desktop            |

---

## Quick Start (Docker Compose — recommended)

```bash
# 1. Clone the repo
git clone https://github.com/yourorg/url-shortener.git
cd url-shortener

# 2. Generate a development RSA keypair (or use the bundled dev keys)
./scripts/gen-dev-keys.sh        # creates dev-keys/ directory

# 3. Build all services
mvn clean package -DskipTests

# 4. Start the full stack
docker compose -f infrastructure/docker/docker-compose.yml up --build

# 5. Verify everything is running
curl http://localhost/actuator/health      # API Gateway
curl http://localhost:8080/actuator/health # URL Service
curl http://localhost:8081/actuator/health # Auth Service
curl http://localhost:8082/actuator/health # Analytics Service
```

Services will be available at:

| Service          | URL                                  |
|------------------|--------------------------------------|
| API Gateway      | http://localhost                     |
| Swagger UI       | http://localhost:8080/swagger-ui.html|
| Grafana          | http://localhost:3000  (admin/admin) |
| Prometheus       | http://localhost:9090                |
| Kafka UI         | http://localhost:8090                |

---

## Quick Start (Local Dev — without Docker)

```bash
# Start infra only (PostgreSQL, Redis, Kafka)
docker compose -f infrastructure/docker/docker-compose.yml \
  up postgres redis zookeeper kafka -d

# Run url-service
cd url-service
SPRING_PROFILES_ACTIVE=development mvn spring-boot:run

# Run auth-service (separate terminal)
cd auth-service
SPRING_PROFILES_ACTIVE=development mvn spring-boot:run

# Run analytics-service (separate terminal)
cd analytics-service
SPRING_PROFILES_ACTIVE=development mvn spring-boot:run
```

---

## API Usage Examples

### Register + Login

```bash
# Register
curl -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","username":"myuser","password":"SecurePass123!"}'

# Login → get access token
TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"SecurePass123!"}' \
  | jq -r '.accessToken')

echo "Token: $TOKEN"
```

### Shorten a URL

```bash
# Anonymous (no auth required)
curl -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"originalUrl":"https://www.example.com/very/long/path"}'

# Authenticated (with custom alias + expiry)
curl -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "originalUrl":  "https://www.example.com/product-launch",
    "customAlias":  "my-launch",
    "expiresAt":    "2025-12-31T23:59:59Z",
    "utmSource":    "twitter",
    "utmMedium":    "social"
  }'
```

### Redirect

```bash
# Follow redirect (default)
curl -L http://localhost:8080/aB3kX7Y

# Inspect redirect without following
curl -I http://localhost:8080/aB3kX7Y
# → HTTP/1.1 302 Found
# → Location: https://www.example.com/product-launch
```

### Analytics

```bash
curl http://localhost:8082/api/v1/analytics/aB3kX7Y
# → {"shortCode":"aB3kX7Y","totalClicks":42,"period":"all-time"}
```

### QR Code

```bash
curl "http://localhost:8080/api/v1/urls/aB3kX7Y/qr?size=300" \
  --output qr.png && open qr.png
```

### List + Manage URLs

```bash
# List your URLs
curl http://localhost:8080/api/v1/urls \
  -H "Authorization: Bearer $TOKEN"

# Delete (deactivate) a URL
curl -X DELETE http://localhost:8080/api/v1/urls/aB3kX7Y \
  -H "Authorization: Bearer $TOKEN"
```

---

## Running Tests

```bash
# Unit tests only (fast, ~5 seconds)
mvn test -pl url-service -Dtest="**unit**"

# Architecture tests
mvn test -pl url-service -Dtest="**ArchitectureTest"

# Full test suite including Testcontainers integration tests (~90 seconds)
mvn verify -pl url-service

# All modules
mvn verify
```

---

## Environment Variables

### url-service

| Variable              | Default                                        | Description                        |
|-----------------------|------------------------------------------------|------------------------------------|
| `DB_URL`              | `jdbc:postgresql://localhost:5432/urlshortener`| PostgreSQL JDBC URL                |
| `DB_USERNAME`         | `shortener`                                    | DB username                        |
| `DB_PASSWORD`         | `devpassword`                                  | DB password                        |
| `REDIS_HOST`          | `localhost`                                    | Redis hostname                     |
| `REDIS_PORT`          | `6379`                                         | Redis port                         |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092`                           | Kafka brokers                      |
| `JWT_PUBLIC_KEY`      | (dev key in application-development.yml)       | RS256 public key PEM               |
| `APP_BASE_URL`        | `https://sho.rt`                               | Base URL for short links           |
| `WORKER_ID`           | `1`                                            | Snowflake worker ID (unique/pod)   |
| `BLOCKED_DOMAINS`     | (empty)                                        | Comma-separated blocked domains    |

### auth-service

| Variable         | Default    | Description                                  |
|------------------|------------|----------------------------------------------|
| `JWT_PRIVATE_KEY`| (dev key)  | RS256 private key PEM (KEEP SECRET)          |
| `JWT_PUBLIC_KEY` | (dev key)  | RS256 public key PEM (shared with url-service)|

---

## Generating Production RSA Keys

```bash
# Generate 2048-bit RSA private key (PKCS#8 format)
openssl genrsa 2048 | openssl pkcs8 -topk8 -nocrypt -out private.pem

# Extract public key
openssl rsa -in private.pem -pubout -out public.pem

# Store in environment / K8s Secret / HashiCorp Vault
# NEVER commit private.pem to source control
export JWT_PRIVATE_KEY=$(cat private.pem)
export JWT_PUBLIC_KEY=$(cat public.pem)
```

---

## Project Structure

```
url-shortener-system/
├── pom.xml                        # Root multi-module Maven POM
├── common-lib/                    # Shared: events, exceptions, utils
├── url-service/                   # Core URL shortening + redirect
│   ├── domain/                    # Pure Java — no framework deps
│   │   ├── Url.java               # Aggregate root
│   │   ├── CachedUrl.java         # Redis value object
│   │   └── service/               # Domain services
│   ├── application/               # Use cases, orchestration
│   ├── port/                      # Inbound + outbound port interfaces
│   ├── infrastructure/            # Redis, Kafka, JPA adapters
│   └── api/                       # REST controllers
├── auth-service/                  # JWT issuance, user registration
├── api-gateway/                   # Routing, auth filter, circuit breakers
├── analytics-service/             # Kafka consumer → Redis counters
├── notification-service/          # Email + webhook delivery
└── infrastructure/
    ├── docker/docker-compose.yml  # Full local stack
    ├── prometheus/                # Scrape config + alert rules
    ├── grafana/                   # Dashboard provisioning
    └── k8s/                       # Kubernetes manifests (Part 3)
```

---

## Key Design Decisions

| Decision                 | Choice                   | Reason                                               |
|--------------------------|--------------------------|------------------------------------------------------|
| ID generation            | Snowflake → Base62       | Monotonic, collision-free, B-tree friendly           |
| Cache hierarchy          | Caffeine → Redis → PG    | L0 ~0.01ms, L1 ~1ms, L2 ~10ms                      |
| Click tracking           | Kafka async              | Never blocks redirect response                       |
| JWT algorithm            | RS256 (asymmetric)       | Services verify with public key; no secret sharing   |
| Rate limiting            | Redis Lua token bucket   | Atomic, distributed, single round-trip               |
| DB migrations            | Flyway                   | Version-controlled, repeatable, validates on startup |
| Architecture             | Hexagonal (ports+adapters)| Testable domain logic; swap Redis/Kafka freely      |
| Concurrency              | Java 21 virtual threads  | 100k+ concurrent connections; no thread-per-request |

---

## Monitoring

After starting the stack:

- **Grafana**: http://localhost:3000 → Dashboards → URL Shortener Overview
- **Prometheus**: http://localhost:9090 → Status → Targets (verify all UP)
- **Kafka UI**: http://localhost:8090 → Topics → url.clicked (watch events arrive)

Key metrics to watch:
```promql
# p99 redirect latency
histogram_quantile(0.99, rate(url_redirect_duration_bucket[5m]))

# Cache hit rate
rate(cache_l1_hit_total[5m]) / (rate(cache_l1_hit_total[5m]) + rate(cache_l1_miss_total[5m]))

# URL creation rate
rate(url_created_total[1m])

# Error rate
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
```

---

## Production Deployment

For Kubernetes deployment, see the manifests in `infrastructure/k8s/`:

```bash
# Apply base manifests
kubectl apply -k infrastructure/k8s/overlays/production

# Check rollout
kubectl rollout status deployment/url-service -n shortener

# Watch pods
kubectl get pods -n shortener -w
```

Full K8s configuration documented in **Part 3** and **Part 4** of the series.

---

## License

MIT — build something great with it.
