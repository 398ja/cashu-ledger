# Changelog

All notable changes to this project are documented here. This project follows Conventional Commits and semantic versioning.

## [Unreleased]

### Added
- Initial project structure for cashu-ledger (core + CLI modules)
- Relay connection manager backed by nostr-java clients
- Voucher event mapping to domain model with parent contributions
- `inspect` CLI command with basic text output formatting
- JSON and tree output formats for `inspect` (select via `--output`)
- Test logging binding to silence SLF4J warnings in unit tests
- Voucher tree traversal (parents/children) and `tree` CLI command with text/JSON output
