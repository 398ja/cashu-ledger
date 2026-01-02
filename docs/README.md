# Cashu Ledger Documentation

Documentation for cashu-ledger, a tool for inspecting and analyzing Cashu vouchers stored on Nostr relays.

## Quick Links

- **[Getting Started](tutorials/getting-started.md)** - Install and run your first voucher inspection
- **[CLI Reference](reference/cli-commands.md)** - Complete command reference
- **[API Reference](reference/rest-api.md)** - REST API endpoints

---

## Documentation Structure

This documentation follows the [Diátaxis](https://diataxis.fr/) framework, organized into four categories:

### Tutorials

Learning-oriented guides that take you through a series of steps to complete a project.

| Document | Description |
|----------|-------------|
| [Getting Started](tutorials/getting-started.md) | Install cashu-ledger and inspect your first voucher |

### How-to Guides

Task-oriented guides that show you how to solve specific problems.

| Document | Description |
|----------|-------------|
| [Enable Local Caching](how-to/enable-local-caching.md) | Configure nostrdb-jni for persistent event caching |
| [Deploy with Docker](how-to/docker-deployment.md) | Run cashu-ledger-web in a container |

### Reference

Technical descriptions of the system and its components.

| Document | Description |
|----------|-------------|
| [CLI Commands](reference/cli-commands.md) | Complete CLI command reference |
| [REST API](reference/rest-api.md) | Web module REST endpoints |
| [Voucher Specification](reference/voucher-specification.md) | Voucher state model and Nostr event format |
| [Configuration](reference/configuration.md) | Configuration properties reference |

### Explanation

Background information and design decisions.

| Document | Description |
|----------|-------------|
| [Architecture Overview](explanation/architecture.md) | System design and component interactions |
| [nostrdb-jni Integration](explanation/nostrdb-integration.md) | Design rationale for the caching layer |
| [State Machine Design](explanation/state-machine.md) | Voucher lifecycle and state transitions |

---

## Project Structure

```
cashu-ledger/
├── cashu-ledger-core/           # Domain models, services, relay connectivity
├── cashu-ledger-cli/            # Picocli command-line interface
├── cashu-ledger-web/            # Spring Boot REST API & Web UI
├── cashu-ledger-e2e-tests/      # End-to-end tests
└── cashu-ledger-integration-tests/  # Integration tests
```

## External Resources

- [Cashu NUT Specifications](https://github.com/cashubtc/nuts) - Protocol specifications
- [nostr-java](https://github.com/nostr-java/nostr-java) - Nostr library for Java
- [nostrdb](https://github.com/damus-io/nostrdb) - High-performance Nostr database
