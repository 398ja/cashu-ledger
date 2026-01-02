# Getting Started with Cashu Ledger

This tutorial walks you through installing cashu-ledger and inspecting your first voucher.

## Prerequisites

- Java 21 or later
- Maven 3.9+ (for building from source)
- Network access to Nostr relays

## Installation

### Option 1: Download Pre-built JAR

Download the latest release from the releases page and run directly:

```bash
java -jar cashu-ledger-cli-0.2.0.jar --help
```

### Option 2: Build from Source

Clone and build the project:

```bash
git clone https://github.com/tcheeric/cashu-ledger.git
cd cashu-ledger
mvn clean package -DskipTests
```

The CLI JAR is located at `cashu-ledger-cli/target/cashu-ledger-cli-0.2.0.jar`.

### Option 3: Run Web Module with Docker

Pull and run the web interface:

```bash
docker pull docker.398ja.xyz/cashu-ledger-web:0.2.0
docker run -p 6060:6060 docker.398ja.xyz/cashu-ledger-web:0.2.0
```

Access the web UI at `http://localhost:6060`.

## Your First Voucher Inspection

### Using the CLI

Inspect a voucher by its ID:

```bash
java -jar cashu-ledger-cli-0.2.0.jar inspect v-1766748473969
```

Example output:

```
Voucher: v-1766748473969
=======================
Status:           issued
Issuer ID:        merchant-001
Issuer PubKey:    a1b2c3d4...

Value:
  Face Value:     10.00 EUR
  Token Amount:   1000 sats
  Issuance Ratio: 1.0

Lifecycle:
  Issued At:      2025-12-26T10:30:00Z
  Expires At:     2026-01-26T10:30:00Z

Event Metadata:
  Event ID:       abc123...
  Relay:          wss://relay.imani.casa
```

### Specifying a Relay

Use a different relay with the `-r` option:

```bash
java -jar cashu-ledger-cli-0.2.0.jar -r wss://nos.lol inspect v-1766748473969
```

### Viewing the Voucher Tree

See the complete hierarchy of a voucher:

```bash
java -jar cashu-ledger-cli-0.2.0.jar tree v-1766748473969
```

Example output:

```
Voucher Hierarchy for v-1766748473969
=====================================

v-1766748470000 [50.00 EUR, 5000 sats] SPLIT (root)
├── v-1766748471000 [20.00 EUR, 2000 sats] REDEEMED
└── v-1766748472000 [30.00 EUR, 3000 sats] SPLIT
    ├── v-1766748473969 [10.00 EUR, 1000 sats] ISSUED <- (target)
    └── v-1766748474000 [20.00 EUR, 2000 sats] CLAIMED
```

### Checking History

View the status change history:

```bash
java -jar cashu-ledger-cli-0.2.0.jar history v-1766748473969
```

## Using the Web Interface

1. Open `http://localhost:6060` in your browser
2. Enter a voucher ID in the search box
3. Click **Inspect** to view details
4. Use the tabs to switch between Inspect, Tree, History, and Search views

## Next Steps

- [Enable Local Caching](../how-to/enable-local-caching.md) - Speed up queries with persistent caching
- [CLI Commands Reference](../reference/cli-commands.md) - Complete command documentation
- [REST API Reference](../reference/rest-api.md) - Web module endpoints
