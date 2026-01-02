# Configuration Reference

Complete reference for cashu-ledger configuration options.

## Web Module (Spring Boot)

### Application Properties

Configure via `application.yml` or environment variables:

```yaml
ledger:
  web:
    # Relay Configuration
    relays:
      - wss://relay.imani.casa
    timeout: 30s
    cache-ttl: 30s

    # Storage Configuration
    storage:
      enabled: false
      path: ${user.home}/.cashu-ledger/ndb
      max-size-bytes: 536870912
      event-ttl: 30d

# Server Configuration
server:
  port: 6060
```

### Environment Variables

All properties can be set via environment variables using the Spring Boot relaxed binding:

| Property | Environment Variable | Description | Default |
|----------|---------------------|-------------|---------|
| `ledger.web.relays` | `LEDGER_WEB_RELAYS` | Comma-separated relay URLs | `wss://relay.imani.casa` |
| `ledger.web.timeout` | `LEDGER_WEB_TIMEOUT` | Connection timeout | `30s` |
| `ledger.web.cache-ttl` | `LEDGER_WEB_CACHE_TTL` | In-memory cache TTL | `30s` |
| `ledger.web.storage.enabled` | `LEDGER_WEB_STORAGE_ENABLED` | Enable persistent caching | `false` |
| `ledger.web.storage.path` | `LEDGER_WEB_STORAGE_PATH` | Database directory | `~/.cashu-ledger/ndb` |
| `ledger.web.storage.max-size-bytes` | `LEDGER_WEB_STORAGE_MAX_SIZE` | Max database size | `536870912` |
| `ledger.web.storage.event-ttl` | `LEDGER_WEB_STORAGE_TTL` | Event retention period | `30d` |
| `server.port` | `SERVER_PORT` | HTTP server port | `6060` |

### Duration Format

Duration values support various formats:
- `30s` - 30 seconds
- `5m` - 5 minutes
- `1h` - 1 hour
- `30d` - 30 days
- `PT30S` - ISO-8601 format

---

## CLI Module

### Command-Line Options

| Option | Description | Default |
|--------|-------------|---------|
| `-r, --relay <URL>` | Nostr relay URL (repeatable) | `wss://relay.imani.casa` |
| `-o, --output <format>` | Output format: `text`, `json`, `tree` | `text` |
| `-v, --verbose` | Enable verbose logging | `false` |
| `--timeout <seconds>` | Connection timeout | `30` |
| `--storage-path <path>` | Local event store path | `~/.cashu-ledger/ndb` |
| `--no-cache` | Disable local caching | `false` |

### Configuration File

The CLI reads configuration from `~/.cashu-ledger/config.properties`:

```properties
# Default relays
ledger.default-relays=wss://relay.imani.casa,wss://nos.lol

# Timeouts
ledger.connection-timeout=30s
ledger.query-timeout=60s

# Tree traversal limits
ledger.max-tree-depth=20

# Output
ledger.output-format=text

# Storage
ledger.storage.enabled=true
ledger.storage.path=${user.home}/.cashu-ledger/ndb
```

---

## Storage Configuration

### Database Settings

| Property | Description | Default | Notes |
|----------|-------------|---------|-------|
| `enabled` | Enable persistent caching | `false` | Set to `true` for production |
| `path` | Database directory | `~/.cashu-ledger/ndb` | Must be writable |
| `max-size-bytes` | Maximum database size | `536870912` (512MB) | Increase for large deployments |
| `event-ttl` | Event retention period | `30d` | Events older than TTL may be evicted |

### Platform Requirements

| Platform | Native Library Requirements |
|----------|----------------------------|
| Linux | glibc 2.17+, x64 or arm64 |
| macOS | 10.13+, x64 or arm64 |
| Windows | Visual C++ Runtime, x64 only |

---

## Logging Configuration

### Log Levels

Configure via `application.yml`:

```yaml
logging:
  level:
    root: INFO
    xyz.tcheeric.cashu.ledger: DEBUG
    xyz.tcheeric.cashu.ledger.core.storage: DEBUG
    xyz.tcheeric.cashu.ledger.core.relay: DEBUG
```

Or via environment variable:

```bash
LOGGING_LEVEL_XYZ_TCHEERIC_CASHU_LEDGER=DEBUG
```

### Log Patterns

| Logger | Purpose |
|--------|---------|
| `xyz.tcheeric.cashu.ledger.core.storage` | Storage operations, cache hits/misses |
| `xyz.tcheeric.cashu.ledger.core.relay` | Relay connections, queries |
| `xyz.tcheeric.cashu.ledger.core.service` | Service-level operations |
| `xyz.tcheeric.cashu.ledger.web` | REST API requests |

---

## JVM Configuration

### Container-Optimized Settings

For Docker deployments, use these JVM flags:

```bash
JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
```

| Flag | Description |
|------|-------------|
| `-XX:+UseContainerSupport` | Respect container memory limits |
| `-XX:MaxRAMPercentage=75.0` | Use 75% of container memory for heap |
| `-XX:+ExitOnOutOfMemoryError` | Exit on OOM for container restart |
| `-Djava.security.egd=...` | Faster random number generation |

### Memory Sizing

Recommended memory settings based on deployment size:

| Deployment | Container Memory | Heap (75%) | Notes |
|------------|-----------------|------------|-------|
| Development | 256MB | 192MB | Minimal, single relay |
| Small | 512MB | 384MB | Standard deployment |
| Medium | 1GB | 768MB | Multiple relays, caching |
| Large | 2GB | 1.5GB | High-traffic, large cache |

---

## Security Configuration

### API Authentication (Planned)

Future releases will support API key authentication:

```yaml
ledger:
  web:
    security:
      api-key-enabled: true
      api-key-header: X-API-Key
```

### Rate Limiting (Planned)

```yaml
ledger:
  web:
    rate-limit:
      enabled: true
      requests-per-minute: 100
```

---

## Example Configurations

### Development

```yaml
ledger:
  web:
    relays:
      - wss://relay.imani.casa
    timeout: 30s
    storage:
      enabled: false

logging:
  level:
    xyz.tcheeric.cashu.ledger: DEBUG

server:
  port: 6060
```

### Production

```yaml
ledger:
  web:
    relays:
      - wss://relay.imani.casa
      - wss://nos.lol
    timeout: 60s
    cache-ttl: 5m
    storage:
      enabled: true
      path: /var/lib/cashu-ledger/ndb
      max-size-bytes: 1073741824  # 1GB
      event-ttl: 90d

logging:
  level:
    root: WARN
    xyz.tcheeric.cashu.ledger: INFO

server:
  port: 6060
```

## Related Documentation

- [Enable Local Caching](../how-to/enable-local-caching.md)
- [Docker Deployment](../how-to/docker-deployment.md)
- [CLI Commands Reference](cli-commands.md)
