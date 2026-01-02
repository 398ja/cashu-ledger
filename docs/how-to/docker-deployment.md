# Deploy Cashu Ledger with Docker

This guide shows how to deploy cashu-ledger-web using Docker for production environments.

## Quick Start

Run the web interface with default settings:

```bash
docker run -p 6060:6060 docker.398ja.xyz/cashu-ledger-web:0.2.0
```

Access at `http://localhost:6060`.

## Production Deployment

### With Persistent Caching

Enable nostrdb caching for improved performance:

```bash
docker run -d \
  --name cashu-ledger-web \
  -p 6060:6060 \
  -e LEDGER_WEB_STORAGE_ENABLED=true \
  -e LEDGER_WEB_STORAGE_PATH=/app/data/ndb \
  -v ledger-cache:/app/data \
  docker.398ja.xyz/cashu-ledger-web:0.2.0
```

### With Custom Relays

Configure relay connections:

```bash
docker run -d \
  --name cashu-ledger-web \
  -p 6060:6060 \
  -e LEDGER_WEB_RELAYS=wss://relay.example.com,wss://nos.lol \
  -e LEDGER_WEB_TIMEOUT=60s \
  docker.398ja.xyz/cashu-ledger-web:0.2.0
```

## Docker Compose

Create a `docker-compose.yml`:

```yaml
version: '3.8'

services:
  cashu-ledger-web:
    image: docker.398ja.xyz/cashu-ledger-web:0.2.0
    container_name: cashu-ledger-web
    ports:
      - "6060:6060"
    environment:
      # Storage configuration
      LEDGER_WEB_STORAGE_ENABLED: "true"
      LEDGER_WEB_STORAGE_PATH: /app/data/ndb
      LEDGER_WEB_STORAGE_MAX_SIZE: "536870912"  # 512MB
      LEDGER_WEB_STORAGE_TTL: "30d"

      # Relay configuration
      LEDGER_WEB_RELAYS: wss://relay.imani.casa
      LEDGER_WEB_TIMEOUT: 30s

      # JVM settings
      JAVA_OPTS: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
    volumes:
      - ledger-cache:/app/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:6060/actuator/health"]
      interval: 30s
      timeout: 10s
      start_period: 30s
      retries: 3
    restart: unless-stopped

volumes:
  ledger-cache:
```

Start the service:

```bash
docker-compose up -d
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `LEDGER_WEB_RELAYS` | Comma-separated list of Nostr relay URLs | `wss://relay.imani.casa` |
| `LEDGER_WEB_TIMEOUT` | Connection timeout | `30s` |
| `LEDGER_WEB_CACHE_TTL` | In-memory cache TTL | `30s` |
| `LEDGER_WEB_STORAGE_ENABLED` | Enable persistent caching | `false` |
| `LEDGER_WEB_STORAGE_PATH` | Database directory path | `~/.cashu-ledger/ndb` |
| `LEDGER_WEB_STORAGE_MAX_SIZE` | Maximum database size (bytes) | `536870912` |
| `LEDGER_WEB_STORAGE_TTL` | Event retention period | `30d` |
| `JAVA_OPTS` | JVM options | Container-optimized defaults |

## Health Checks

The application exposes health endpoints via Spring Boot Actuator:

- `GET /actuator/health` - Overall health status
- `GET /actuator/health/liveness` - Kubernetes liveness probe
- `GET /actuator/health/readiness` - Kubernetes readiness probe

## Reverse Proxy (nginx)

Example nginx configuration:

```nginx
server {
    listen 80;
    server_name ledger.example.com;

    location / {
        proxy_pass http://localhost:6060;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # SSE support for watch endpoint
        proxy_set_header Connection '';
        proxy_buffering off;
        proxy_cache off;
    }
}
```

## Kubernetes Deployment

Example Kubernetes manifests:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cashu-ledger-web
spec:
  replicas: 1
  selector:
    matchLabels:
      app: cashu-ledger-web
  template:
    metadata:
      labels:
        app: cashu-ledger-web
    spec:
      containers:
      - name: cashu-ledger-web
        image: docker.398ja.xyz/cashu-ledger-web:0.2.0
        ports:
        - containerPort: 6060
        env:
        - name: LEDGER_WEB_STORAGE_ENABLED
          value: "true"
        - name: LEDGER_WEB_STORAGE_PATH
          value: /app/data/ndb
        volumeMounts:
        - name: cache-storage
          mountPath: /app/data
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 6060
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 6060
          initialDelaySeconds: 10
          periodSeconds: 5
      volumes:
      - name: cache-storage
        persistentVolumeClaim:
          claimName: ledger-cache-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: cashu-ledger-web
spec:
  selector:
    app: cashu-ledger-web
  ports:
  - port: 80
    targetPort: 6060
```

## Troubleshooting

### Container Won't Start

Check logs:
```bash
docker logs cashu-ledger-web
```

Common issues:
- Port 6060 already in use
- Insufficient memory (increase Docker memory limit)
- Volume permissions (ensure container can write to mounted volume)

### Health Check Failing

Verify the application is responding:
```bash
curl http://localhost:6060/actuator/health
```

Increase `start_period` if the application needs more time to initialize.

### Cache Not Persisting

Verify the volume is mounted correctly:
```bash
docker exec cashu-ledger-web ls -la /app/data
```

## Related Documentation

- [Enable Local Caching](enable-local-caching.md)
- [Configuration Reference](../reference/configuration.md)
