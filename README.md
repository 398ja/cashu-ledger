# Cashu Ledger

A tool for inspecting and analyzing Cashu vouchers stored on Nostr relays.

## Features

- **Voucher Inspection** - View detailed voucher information including status, values, and metadata
- **Tree Traversal** - Navigate parent-child relationships to understand voucher hierarchies
- **History Tracking** - View status change history for auditing and debugging
- **Search** - Find vouchers by issuer, status, value, and other criteria
- **Verification** - Validate signatures and value conservation
- **Local Caching** - Optional persistent caching with nostrdb-jni for sub-millisecond queries

## Quick Start

### CLI

```bash
# Build from source
mvn clean package -DskipTests

# Inspect a voucher
java -jar cashu-ledger-cli/target/cashu-ledger-cli-0.2.0.jar inspect v-1766748473969

# View voucher tree
java -jar cashu-ledger-cli/target/cashu-ledger-cli-0.2.0.jar tree v-1766748473969
```

### Docker (Web UI)

```bash
docker run -p 6060:6060 docker.398ja.xyz/cashu-ledger-web:0.2.0
```

Access the web interface at `http://localhost:6060`.

## Project Structure

```
cashu-ledger/
├── cashu-ledger-core/           # Domain models, services, relay connectivity
├── cashu-ledger-cli/            # Picocli command-line interface
├── cashu-ledger-web/            # Spring Boot REST API & Web UI
├── cashu-ledger-e2e-tests/      # End-to-end tests
└── cashu-ledger-integration-tests/  # Integration tests
```

## Requirements

- Java 21 or later
- Maven 3.9+ (for building)
- Network access to Nostr relays

## Configuration

### CLI Options

```bash
cashu-ledger [OPTIONS] <command> [ARGS]

Options:
  -r, --relay <URL>      Nostr relay URL (default: wss://relay.imani.casa)
  -o, --output <format>  Output format: text, json, tree
  -v, --verbose          Enable verbose logging
  --storage-path <path>  Path to local cache database
  --no-cache             Disable local caching
```

### Web Module

Configure via environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `LEDGER_WEB_RELAYS` | Nostr relay URLs | `wss://relay.imani.casa` |
| `LEDGER_WEB_STORAGE_ENABLED` | Enable persistent caching | `false` |
| `LEDGER_WEB_STORAGE_PATH` | Cache database path | `~/.cashu-ledger/ndb` |

## Performance

With local caching enabled (nostrdb-jni):

| Operation | Without Cache | With Cache |
|-----------|---------------|------------|
| Single voucher fetch | 100-500ms | <1ms |
| Tree traversal (depth 5) | 2-10s | <10ms |
| History (100 events) | 500ms-2s | <5ms |

## Documentation

Full documentation is available in the [docs](docs/) directory:

- [Getting Started](docs/tutorials/getting-started.md)
- [CLI Reference](docs/reference/cli-commands.md)
- [REST API Reference](docs/reference/rest-api.md)
- [Configuration](docs/reference/configuration.md)
- [Architecture](docs/explanation/architecture.md)

## Development

### Building

```bash
mvn clean package
```

### Running Tests

```bash
mvn verify
```

### Building Docker Image

```bash
cd cashu-ledger-web
mvn jib:build
```

## License

MIT License - see [LICENSE](LICENSE) for details.

## Related Projects

- [cashu-client](https://github.com/tcheeric/cashu-client) - Cashu wallet implementation
- [nostr-java](https://github.com/nostr-java/nostr-java) - Nostr library for Java
- [nostrdb-jni](https://github.com/tcheeric/nostrdb-jni) - Java bindings for nostrdb
