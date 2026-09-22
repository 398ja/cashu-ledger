# Changelog

All notable changes to this project are documented here. This project follows Conventional Commits and semantic versioning.

## [0.7.2] - 2026-09-22

### Security

- **BouncyCastle 1.84 -> 1.85 for CVE-2026-8763 (CRITICAL)**, via `imani-bom` 0.1.97.
  X.509 Name Constraints can be bypassed with a trailing dot in an `rfc822Name` or URI, so a
  certificate can assert a name the constraint exists to forbid. Verified with
  `dependency:tree` rather than by reading the pom.

### Changed

- `imani-bom` 0.1.77 -> 0.1.97. A twenty-version jump, so this release carries whatever else
  that range changed; the full suite passes on it.

## [0.7.1] - 2026-09-15

### Security

- **`io.undertow:undertow-core` overridden to 2.3.21.Final** to clear CVE-2025-12543
  (CRITICAL). It arrives test-scoped and transitively via `testcontainers` 1.21.4, which pins
  the affected 2.3.18.Final, and nothing here declares undertow directly, so a
  `dependencyManagement` override is the only lever this repository has. Test scope, so it is
  not shipped and not reachable by a deployed ledger -- but it runs in CI and on developer
  machines. Remove the override once Testcontainers ships a release pulling a fixed undertow.

  Found because the dependency scan ran on `master` for the first time: the workflow had been
  keyed to a `main` branch that does not exist in this repository.

### Fixed

- **Workflow triggers point at `master`.** `dependency-scan`, `enforce_conventional_commits`,
  `google-java-format` and `qodana` all triggered on `main`, which this repository has never
  had, so none of them ran on a push to the default branch. The vulnerability scan's only runs
  came from its weekly schedule.
- **The SBOM guard no longer depends on the runner's default shell flags**, and the SARIF upload
  is allowed to fail on this private repository without failing the scan job, while the scan's
  own outcome is still asserted.

## [0.7.0] - 2026-09-14

Two findings from the 2026-09-13 AppSec review of the estate.

**Minor, not patch: the filter chain now states its authorization boundary**, and denies anything
nobody has classified. A route added under an unclassified prefix is now refused rather than
silently public.

### Security

- **The authorization boundary was not written where authorization is configured.** The chain
  said `anyRequest().permitAll()`, with every access decision made inside a handler. This was
  never an open door — `Nip98AuthenticationFilter` rejects unauthenticated requests to
  `/api/v1/trace` before a controller sees them, and both handlers fail closed — which is why the
  finding was downgraded to Low once measured.

  What was missing is that the boundary lived in a filter's path prefix and in per-handler
  throws, so an admin route added under a different prefix, or the prefix being renamed, would be
  unauthenticated with nothing failing to compile or to test. The chain now states it:
  `trace:admin` for the admin surface, `authenticated` for the rest of the trace prefix, explicit
  `permitAll` for the public voucher reads and the login page, and **deny for anything nobody has
  classified**.

  That last rule required a real change. `Nip98AuthenticationFilter` published its principal only
  as a request attribute, never to Spring's `SecurityContext`, so `.authenticated()` would have
  rejected even a validly signed request. It now sets an `Authentication` with authorities named
  after `TraceAuthority`, which is what lets the chain express the rule at all.

- **Dependabot alerts were disabled.** Enabling them surfaced 6, including a runtime
  `logback-core` cluster. `logback.version` 1.5.18 -> 1.5.34 clears all four of those. The
  CRITICAL is a false positive: CVE-2026-33634 needs `trivy-action` < 0.35.0.

## [0.6.0] - 2026-09-06

Security remediation from the 2026-09-05 audit, plus the defects an adversarial review of that
remediation found. Minor rather than patch: `verify` now fails until issuer keys are configured,
and operational endpoints require authentication.

### Security

- **A registered issuer key is required before a signature is called valid** (audit H-12).

- **NIP-98 auth events are single-use, and operational endpoints are gated** (audit M-25, L-28).
  `/actuator/prometheus`, `/v3/api-docs` and `/swagger-ui` were reachable unauthenticated.

- **The operational endpoint filter matches the context-relative path.** It compared
  `getRequestURI()`, which includes the context path, so under any non-root
  `server.servlet.context-path` every prefix missed and the filter permitted everything while
  remaining registered and appearing to work.

- **The nsec field is cleared on failed login and store permissions are restricted** (audit L-25,
  L-29).

### Fixed

- **The issuer key registry is configurable.** `IssuerAttestationConfig` was constructed as
  `empty()` at all nine CLI call sites and the sole web bean, with no property, option or binding
  anywhere, so every `verify` invocation exited non-zero with no supported remedy: not fail-closed
  but fail-always, and a silent break for any CI job gating on that exit code. Adds
  `--issuer-key <id>=<hex>` and `ledger.web.issuer-keys.<id>`, with a startup warning when empty.

- **A 2.4%-per-run flake in the session envelope test.** It asserted that the two-character
  password `pw` did not appear in roughly a hundred positions of random base64.

### Changed

- **`cashu-voucher` 0.10.0 -> 0.14.0, `cashu-lib-crypto` 0.21.0 -> 0.30.0, `cashu-wallet-client`
  0.6.4 -> 0.8.0** (audit L-36). The ledger held three separate stale pins, and the effect was
  worse than being behind: `cashu-lib-common` and `-entities` resolved to 0.30.0 through the
  voucher while `-crypto` stayed at 0.21.0, so the modules carrying the constant-time scalar
  multiplication and the on-curve key check were nine minor versions older than the ones calling
  them. All three now resolve 0.30.0.

## [0.5.0] - 2026-08-31

Released as 0.5.0 rather than 0.4.0. The repository's pom said `0.4.0`, but no
0.4.0 was ever published: reposilite held only 0.3.0, so both 0.3.1 and 0.4.0
existed as changelog entries and nothing else. Publishing this tree as 0.4.0
would have shipped an artifact that the 0.4.0 entry below does not describe,
because the tree has since gained the publisher meters and a cashu-lib-crypto
jump. Consumers crossing 0.3.0 to here therefore also take 0.3.1's relay-display
fix and 0.4.0's nsec login, including its removal of NIP-07 sign-in.

### Added

- The spec-048 publisher meters, `gateway_trace_publisher_outbox_depth` and
  `gateway_trace_publisher_publish_attempts_total`. Three alert rules in
  imani-deploy already matched on them and neither meter existed, so none could
  fire. `OutboxDepthAlertTest` drove an in-test counter and asserted the same
  expression *shape*, which checks the alert logic and nothing about whether a
  scrape can produce the series.

  Micrometer is an **optional** dependency and the meters sit behind
  `@ConditionalOnClass`, matching the existing OpenTelemetry decorator, so a
  consumer without it keeps the whole publisher stack minus the meters.
  `OutboxDispatcher` reports outcomes through a small `PublishOutcomeListener`
  rather than taking a metrics dependency of its own.

  **Consumers must upgrade to pick this up.** imani-gateway-customer resolves
  this module at 0.3.0 through `imani-bom`, so the meters arrive there only
  once the BOM advances past this release.

### Changed

- Updated cashu-lib-crypto to 0.21.0 (was 0.9.1).

## [0.4.0] - 2026-06-22

### Added

- **nsec login for the web Transaction Graph** — sign in with your `nsec1…` private key and a password instead of a browser extension. The nsec is encrypted in the browser (WebCrypto PBKDF2-SHA256 + AES-256-GCM) with the password and persisted locally; while unlocked, the in-memory key signs the NIP-98 headers the graph requests require.
  - Returning visits unlock with the password only; an incorrect password is rejected with the stored credential kept.
  - Sessions auto-lock after 15 minutes of inactivity, clearing the in-memory key while keeping the stored credential; logout wipes the stored credential and key and locks any other open tabs.
  - Client-side NIP-19 decoding and NIP-01/BIP-340 signing use the vendored, audited `nostr-tools` bundle; a `node --test` suite covers the crypto envelope and nsec handling and runs under `mvn verify`.
  - The graph explorer now targets the `/api/v1` trace API by default (decoupled from the voucher `/proxy` base), so searches work without manually changing the API base; still overridable via `data-trace-api-base` or a browser-local setting.

### Removed

- **NIP-07 browser-extension sign-in** for the Transaction Graph — replaced entirely by nsec login. The web UI no longer depends on a `window.nostr` extension being present.

## [0.3.1] - 2026-06-22

### Fixed

- **Ledger web relay display** — the inspection UI relay pill was a hardcoded `wss://relay.imani.casa` literal that ignored configuration. A new `HomeController` now injects the configured `ledger.web.relays` into the page so it reflects the relay the service actually reads from. The stale API-base placeholder was also genericised.

## [0.3.0] - 2026-06-20

### Added

- **Transaction Traceability** — a chain-of-custody DAG over Cashu mint/swap/melt/send/receive operations, published as signed kind-9079 Nostr events and served by an operator-internal read API.
  - New modules `cashu-ledger-trace-core` (schema, canonical JSON, redaction, invariants) and `cashu-ledger-trace-publisher` (embeddable producer SDK: durable outbox, signer, deterministic operation ids, nostr relay transport, Spring Boot starter, OpenTelemetry, reconciler).
  - Ledger ingest (opt-in `trace.ingest.enabled`): relay sync engine, voucher-state watcher, validating ingest with producer attestation, dedup/conflict/clock-skew, SQLite sidecar index, activity classification cache, and issuer back-fill.
  - Read API under `/api/v1/trace` (NIP-98 authenticated, access-shaped): events, operations, proof history, forensic walk, voucher/issuer listings, visualisation graph, stats, relays, SSE stream, and admin redaction-key/access-log/index-status surfaces.
  - `cashu-ledger trace` CLI: `proof` (client-side Y derivation), `voucher`, `issuer`, `replay` (idempotent backfill), `export --sanitise`.
  - Web graph explorer (Cytoscape/dagre) with drill-down, privacy banner, and live updates.
  - Minimal tombstone pruning and a retention engine (age + terminal sub-DAG); Prometheus metrics + Grafana dashboard; OpenAPI spec; schema-evolution four-tier ladder.

### Security

- Trace events are published only to private, authenticated relays; read responses are shaped to the caller's authority so a summary-level caller never receives secret-bearing fields; redaction keys are stored encrypted at rest; sanitised export re-keys secrets under an ephemeral key.

## [0.2.2] - 2026-01-10

### Fixed

- **NIP-01 Compliance** - Improved event validation in NostrDbEventStore using nostr-java's built-in validation

### Changed

- Updated nostrdb-jni dependency from 0.1.0-SNAPSHOT to 0.1.1
- Updated cashu-voucher dependency from 0.3.7 to 0.5.0
- Improved container memory configuration for JVM

## [0.2.1] - 2026-01-02

### Fixed

- **Event Serialization** - Convert GenericEvent to serializable map structure to avoid Jackson NPE with nostr-java objects
- **Web Module Dependency** - Include nostrdb-jni dependency in web module for persistent caching support
- **Serialization Logging** - Reduce serialization failure log level from ERROR to WARN (recoverable operation)
- **Resource Management** - Add `@SuppressWarnings("resource")` for intentionally pooled ClientContext connections
- **CI Test Compatibility** - Skip nostrdb tests in CI environments where LMDB memory allocation fails
  - Add class-level `@EnabledIf` to prevent `@BeforeEach` from running before condition check
  - Affects: `NostrDbEventStoreTest`, `StorageIntegrationTest`, `StorageCachingE2ETest`
- **Docker Port** - Correct health check port from 8080 to 6060 in Dockerfile
- **Build Configuration** - Add missing lombok version in annotationProcessorPaths
- **Tag Serialization** - Handle all BaseTag types with reflection fallback, not just GenericTag
- **JVM Memory Config** - Remove conflicting fixed heap flags (-Xms/-Xmx) in favor of percentage-based sizing for containers
- **NIP-01 Validation** - Use nostr-java's built-in `event.validate()` for NIP-01 compliance with warning logs

### Changed

- Added Maven wrapper (mvnw) for consistent builds across environments

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
- Upgraded Spring Boot from 3.3.4 to 3.5.9 for security fixes
- Docker image now exposes `/app/data` volume for persistent storage
- Web module application.yml uses environment variables for configuration

### Fixed

- **Resource Management** - Proper cleanup of WebSocket clients on reconnection
  - `NostrRelayConnectionManager` now closes clients before removal
  - `createClient()` properly cleans up on failure
- **Stream Resource Leak** - Fixed `Files.walk()` stream in `NostrDbEventStore.estimateDatabaseSize()`
- **Timeout Handling** - `CountDownLatch.await()` results now captured and logged on timeout
- **Bulk Operations** - Replaced forEach with addAll in `VoucherLedgerServiceImpl`
- **Redundant Logic** - Simplified eventId comparison in `VoucherStateJournal`
- **Unused Configuration** - Removed unused `api-base` property from application.yml

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
