# REST API Reference

Complete reference for cashu-ledger-web REST endpoints.

## Base URL

```
http://localhost:6060/api
```

## Authentication

The API currently does not require authentication. Rate limiting may be applied in production.

---

## Endpoints

### Inspect Voucher

Fetch detailed information about a voucher.

```
GET /api/vouchers/{voucherId}
```

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `voucherId` | string | The voucher identifier |

**Response:**

```json
{
  "voucherId": "v-1766748473969",
  "status": "issued",
  "issuerId": "merchant-001",
  "issuerPublicKey": "a1b2c3d4...",
  "faceValue": 1000,
  "faceDecimals": 2,
  "tokenAmount": 1000,
  "originalFaceValue": 1000,
  "originalTokenAmount": 1000,
  "unit": "EUR",
  "backingStrategy": "PROPORTIONAL",
  "issuanceRatio": 1.0,
  "issuedAt": "2025-12-26T10:30:00Z",
  "expiresAt": "2026-01-26T10:30:00Z",
  "memo": "Payment for services",
  "parentContributions": [],
  "eventMetadata": {
    "eventId": "abc123...",
    "pubkey": "a1b2c3d4...",
    "createdAt": "2025-12-26T10:30:05Z",
    "relay": "wss://relay.imani.casa"
  }
}
```

**Status Codes:**

| Code | Description |
|------|-------------|
| 200 | Success |
| 404 | Voucher not found |

---

### Get Voucher Tree

Retrieve the complete hierarchy of a voucher.

```
GET /api/vouchers/{voucherId}/tree
```

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `voucherId` | string | The voucher identifier |

**Query Parameters:**

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `depth` | integer | Maximum depth to traverse | `10` |
| `direction` | string | `up`, `down`, or `both` | `both` |

**Response:**

```json
{
  "target": "v-1766748473969",
  "root": "v-1766748470000",
  "nodes": [
    {
      "voucherId": "v-1766748470000",
      "faceValue": 5000,
      "tokenAmount": 5000,
      "status": "split",
      "children": ["v-1766748471000", "v-1766748472000"],
      "parents": []
    }
  ],
  "depth": 3,
  "totalNodes": 5
}
```

---

### Get Voucher History

Retrieve the status change history for a voucher.

```
GET /api/vouchers/{voucherId}/history
```

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `voucherId` | string | The voucher identifier |

**Query Parameters:**

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `since` | string | Filter events after date (ISO-8601) | none |
| `until` | string | Filter events before date (ISO-8601) | none |
| `limit` | integer | Maximum events to fetch | `100` |

**Response:**

```json
{
  "voucherId": "v-1766748473969",
  "events": [
    {
      "timestamp": "2025-12-26T10:30:00Z",
      "status": "issued",
      "eventId": "abc123...",
      "relay": "wss://relay.imani.casa"
    },
    {
      "timestamp": "2025-12-26T14:15:30Z",
      "status": "claimed",
      "eventId": "def456...",
      "relay": "wss://relay.imani.casa"
    }
  ],
  "totalEvents": 2
}
```

---

### Search Vouchers

Search vouchers by criteria.

```
GET /api/vouchers/search
```

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `issuer` | string | Filter by issuer ID |
| `status` | string | Filter by status |
| `since` | string | Issued after date (ISO-8601) |
| `until` | string | Issued before date (ISO-8601) |
| `minValue` | integer | Minimum face value (minor units) |
| `maxValue` | integer | Maximum face value (minor units) |
| `unit` | string | Filter by currency unit |
| `hasParents` | boolean | Only vouchers with parents |
| `isRoot` | boolean | Only root vouchers |
| `limit` | integer | Maximum results (default: 50) |

**Response:**

```json
{
  "results": [
    {
      "voucherId": "v-1766748473969",
      "faceValue": 1000,
      "unit": "EUR",
      "status": "issued",
      "issuerId": "merchant-001",
      "issuedAt": "2025-12-26T10:30:00Z"
    }
  ],
  "totalResults": 1,
  "limit": 50
}
```

---

### Verify Voucher

Verify the integrity of a voucher.

```
GET /api/vouchers/{voucherId}/verify
```

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `voucherId` | string | The voucher identifier |

**Query Parameters:**

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `checkSignature` | boolean | Verify Ed25519 signature | `true` |
| `checkHierarchy` | boolean | Verify value conservation | `false` |
| `checkExpiry` | boolean | Check expiration | `true` |

**Response:**

```json
{
  "voucherId": "v-1766748473969",
  "passed": true,
  "checks": [
    {
      "name": "signature",
      "passed": true,
      "message": "Valid issuer signature"
    },
    {
      "name": "expiry",
      "passed": true,
      "message": "Expires 2026-01-26"
    }
  ],
  "totalChecks": 5,
  "passedChecks": 5
}
```

---

### Compare Vouchers

Compare two vouchers.

```
GET /api/vouchers/diff
```

**Query Parameters:**

| Parameter | Type | Description | Required |
|-----------|------|-------------|----------|
| `voucher1` | string | First voucher ID | Yes |
| `voucher2` | string | Second voucher ID | Yes |

**Response:**

```json
{
  "voucher1": "v-1766748473969",
  "voucher2": "v-1766748474000",
  "relationship": "siblings",
  "commonParent": "v-1766748472000",
  "differences": {
    "faceValue": [1000, 2000],
    "tokenAmount": [1000, 2000]
  }
}
```

---

### Watch Voucher (SSE)

Subscribe to real-time updates for a voucher using Server-Sent Events.

```
GET /api/vouchers/{voucherId}/watch
```

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `voucherId` | string | The voucher identifier |

**Response:**

```
event: status-change
data: {"voucherId":"v-1766748473969","oldStatus":"issued","newStatus":"claimed","timestamp":"2025-12-26T14:15:30Z"}

event: heartbeat
data: {"timestamp":"2025-12-26T14:16:00Z"}
```

---

### Export Voucher

Export voucher data in various formats.

```
GET /api/vouchers/{voucherId}/export
```

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `voucherId` | string | The voucher identifier |

**Query Parameters:**

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `format` | string | `json` or `csv` | `json` |
| `tree` | boolean | Include full hierarchy | `false` |
| `includeEvents` | boolean | Include raw Nostr events | `false` |

**Response Headers:**

```
Content-Type: application/json (or text/csv)
Content-Disposition: attachment; filename="voucher-v-1766748473969.json"
```

---

## Health Endpoints

### Health Check

```
GET /actuator/health
```

**Response:**

```json
{
  "status": "UP",
  "components": {
    "relay": {
      "status": "UP",
      "details": {
        "connected": true
      }
    },
    "storage": {
      "status": "UP",
      "details": {
        "available": true,
        "events": 1234
      }
    }
  }
}
```

### Liveness Probe

```
GET /actuator/health/liveness
```

### Readiness Probe

```
GET /actuator/health/readiness
```

---

## Error Responses

All errors follow a standard format:

```json
{
  "error": "VOUCHER_NOT_FOUND",
  "message": "Voucher v-invalid-id not found on any configured relay",
  "timestamp": "2025-12-26T14:15:30Z",
  "path": "/api/vouchers/v-invalid-id"
}
```

**Common Error Codes:**

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `VOUCHER_NOT_FOUND` | 404 | Voucher does not exist |
| `RELAY_UNAVAILABLE` | 503 | Cannot connect to relays |
| `INVALID_VOUCHER_ID` | 400 | Invalid voucher ID format |
| `TIMEOUT` | 504 | Query timed out |

## Related Documentation

- [CLI Commands Reference](cli-commands.md)
- [Configuration Reference](configuration.md)
- [Docker Deployment](../how-to/docker-deployment.md)
