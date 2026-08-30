# Cashu Ledger

A tool for inspecting and analyzing Cashu vouchers stored on Nostr relays.

## Features

- **Voucher Inspection** - View detailed voucher information including status, values, and metadata
- **Tree Traversal** - Navigate parent-child relationships to understand voucher hierarchies
- **History Tracking** - View status change history for auditing and debugging
- **Search** - Find vouchers by issuer, status, value, and other criteria
- **Verification** - Validate signatures and value conservation
- **Local Caching** - Optional persistent caching with nostrdb-jni for sub-millisecond queries
- **Forensic trace ledger** - Signed kind-9079 trace events published by producers such as
  cashu-mint and gateway-customer. See [Operate the trace ledger](docs/how-to/operate-trace-ledger.md)
  and the [trace API reference](docs/reference/trace-api.md).

## Quick Start

### CLI

```bash
# Build from source
./mvnw clean package -DskipTests

# Inspect a voucher
java -jar cashu-ledger-cli/target/cashu-ledger-cli-0.4.0.jar inspect v-1766748473969

# View voucher tree
java -jar cashu-ledger-cli/target/cashu-ledger-cli-0.4.0.jar tree v-1766748473969
```

### Docker (Web UI)

```bash
docker run -p 6060:6060 docker.398ja.xyz/cashu-ledger-web:0.4.0
```

Access the web interface at `http://localhost:6060`.

## Project Structure

```
cashu-ledger/
├── cashu-ledger-trace-core/      # Trace event model shared by producers and the ledger
├── cashu-ledger-trace-publisher/ # Producer-side publisher: outbox, signing, redaction
├── cashu-ledger-core/            # Domain models, services, relay connectivity
├── cashu-ledger-cli/             # Picocli command-line interface
├── cashu-ledger-web/             # Spring Boot REST API & Web UI
├── cashu-ledger-integration-tests/
└── cashu-ledger-e2e-tests/
```

`cashu-ledger-trace-publisher` is the module other services embed to emit trace
events; cashu-mint and gateway-customer both depend on it. It carries the
file-backed outbox that lets events survive a producer restart, the signing key
handling, and the redaction that pseudonymises initiator pubkeys.

## Requirements

- Java 21 or later
- Network access to Nostr relays

> **Note:** Maven wrapper is included (`./mvnw`), so Maven installation is optional.

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
- [Trace API Reference](docs/reference/trace-api.md)
- [Configuration](docs/reference/configuration.md)
- [Operate the trace ledger](docs/how-to/operate-trace-ledger.md)
- [Architecture](docs/explanation/architecture.md)
- [Transaction traceability specification](docs/design/transaction-traceability-specification.md)

## Development

### Building

```bash
./mvnw clean package
```

### Running Tests

```bash
./mvnw verify
```

### Building Docker Image

```bash
./mvnw -pl cashu-ledger-web jib:build
```

## License

MIT License - see [LICENSE](LICENSE) for details.

## Related Projects

- [cashu-client](https://github.com/tcheeric/cashu-client) - Cashu wallet implementation
- [nostr-java](https://github.com/nostr-java/nostr-java) - Nostr library for Java
- [nostrdb-jni](https://github.com/tcheeric/nostrdb-jni) - Java bindings for nostrdb
