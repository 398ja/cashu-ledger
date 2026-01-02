# Changelog

All notable changes to this project are documented here. This project follows Conventional Commits and semantic versioning.

## [0.2.0] - 2026-01-02

### Added

- **nostrdb-jni Integration** - High-performance local caching layer backed by LMDB
  - `EventStore` interface and `NostrDbEventStore` implementation
  - `CachingRelayConnectionManager` decorator for transparent caching
  - Sub-millisecond queries for cached voucher events
  - Persistent storage across application restarts
- **Batch Prefetching** - Optimized tree traversal with BFS and batch fetching
  - Reduces relay round-trips for deep hierarchies
  - Tree traversal improved from 2-10s to <10ms with warm cache
- **Configuration Properties**
  - `ledger.web.storage.enabled` - Enable/disable local caching
  - `ledger.web.storage.path` - Database directory path
  - `ledger.web.storage.max-size-bytes` - Maximum database size
  - `ledger.web.storage.event-ttl` - Event retention period
- **CLI Storage Options**
  - `--storage-path` - Custom local cache path
  - `--no-cache` - Disable caching for relay-only mode
- **Graceful Degradation** - Falls back to relay-only mode when native library unavailable
- **Integration Tests** - Storage layer integration tests with conditional execution
- **E2E Tests** - Comprehensive end-to-end tests for caching behavior
- **Dockerfile Updates**
  - Container-optimized JVM flags
  - Health check endpoint support
  - Volume for persistent cache
  - Environment variable configuration
- **Documentation** - Diátaxis-structured documentation
  - Getting started tutorial
  - How-to guides for caching and Docker deployment
  - CLI and REST API reference
  - Architecture and design explanations

### Changed

- Updated cashu-voucher dependency from 0.3.6 to 0.3.7
- Docker image now exposes `/app/data` volume for persistent storage
- Web module application.yml uses environment variables for configuration

## [0.1.0] - 2025-12-26

### Added

- Initial project structure for cashu-ledger (core + CLI modules)
- Relay connection manager backed by nostr-java clients
- Voucher event mapping to domain model with parent contributions
- `inspect` CLI command with basic text output formatting
- JSON and tree output formats for `inspect` (select via `--output`)
- Test logging binding to silence SLF4J warnings in unit tests
- Voucher tree traversal (parents/children) and `tree` CLI command with text/JSON output
- `history` command for viewing voucher status changes
- `search` command for finding vouchers by criteria
- `verify` command for validating voucher integrity
- `diff` command for comparing two vouchers
- `export` command for exporting voucher data
- `watch` command for real-time updates via SSE
- Spring Boot web module with REST API
- Web UI for voucher inspection
- State machine for voucher lifecycle (ISSUED, CLAIMED, SPLIT, REDEEMED, RECLAIMED, REVOKED, EXPIRED)
- State transition validation with guards
- Value conservation verification for split operations
