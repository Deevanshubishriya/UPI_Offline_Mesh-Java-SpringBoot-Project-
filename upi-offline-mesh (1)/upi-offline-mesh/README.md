# UPI Offline Mesh — Demo (rebuilt)

A Spring Boot backend that demonstrates offline UPI-style payments routed through a
Bluetooth-style mesh network: a sender's phone encrypts a payment and hands it to
nearby devices; the packet hops device-to-device until a "bridge" device with internet
uploads it to this backend, which decrypts, deduplicates, and settles it exactly once.

This is a from-scratch reimplementation of the demo described at
https://github.com/perryvegehan/UPI_Without_Internet (that repo's README documents the
design; this project implements it).

## Prerequisites

- JDK 17+ on PATH (`java -version`)
- Nothing else — the Maven wrapper downloads Maven itself on first run.

## Run it

```
./mvnw spring-boot:run        # Mac/Linux
mvnw.cmd spring-boot:run      # Windows
```

Then open **http://localhost:8080**.

## Run the tests

```
./mvnw test
```

`IdempotencyConcurrencyTest` is the interesting one — it fires three threads at
`BridgeIngestionService.ingest()` with the same packet simultaneously and asserts
exactly one settles.

## Demo flow

1. **Compose a payment** — pick sender/receiver/amount/PIN, click **Inject into Mesh**.
   The backend plays the role of the sender's phone: builds a `PaymentInstruction`,
   hybrid-encrypts it (RSA-OAEP wraps a per-packet AES-256 key, AES-GCM encrypts the
   payload), wraps it in a `MeshPacket`, and hands it to `phone-alice`.
2. **Run Gossip Round** (click once or twice) — every device broadcasts what it's
   holding to every other device, TTL decrementing each hop.
3. **Bridges Upload to Backend** — `phone-bridge` is the only device with
   `hasInternet=true`. It POSTs everything it's carrying to `/api/bridge/ingest`,
   which hashes → claims → decrypts → freshness-checks → settles.
4. Watch **Account Balances** and the **Transaction Ledger** update, and the pipeline
   log show `SETTLED` / `DUPLICATE_DROPPED` / `INVALID` outcomes.

## Architecture

```
sender phone ──encrypt(RSA-OAEP+AES-GCM)──▶ MeshPacket ──gossip──▶ ... ──▶ bridge
                                                                              │
                                                                    HTTPS POST│
                                                                              ▼
                                        /api/bridge/ingest (BridgeIngestionService)
                                          1. hash ciphertext (SHA-256)
                                          2. IdempotencyService.claim(hash)   — atomic
                                          3. HybridCryptoService.decrypt()    — RSA-OAEP unwrap + AES-GCM verify
                                          4. freshness check (signedAt < 24h old)
                                          5. SettlementService.settle()       — @Transactional debit/credit/ledger
```

## The three hard problems

1. **Untrusted intermediaries** — solved with hybrid RSA-OAEP + AES-256-GCM. Only the
   server's private key can decrypt; AES-GCM's auth tag makes tampering detectable
   (bad tag ⇒ exception, never silent corruption). See `HybridCryptoService`.
2. **Duplicate storms** — `IdempotencyService.claim()` does an atomic
   `ConcurrentHashMap.putIfAbsent` on the SHA-256 hash of the *ciphertext* (not the
   packet id, which an intermediary could rewrite, and not the cleartext, which needs
   decrypting first). First caller wins; the rest are dropped before any crypto or DB
   work happens. `transactions.packet_hash` also has a unique index as defense in depth.
3. **Replay attacks** — the encrypted payload carries `signedAt` (rejected if >24h old)
   and a `nonce` (so two legitimate identical-amount payments don't collide, but a
   byte-identical replay does — and gets caught by #2).

## File map

```
src/main/java/com/demo/upimesh/
├── UpiMeshApplication.java          Spring Boot entrypoint
├── model/                           Account, Transaction (JPA entities + repos),
│                                     MeshPacket, PaymentInstruction (wire/payload DTOs)
├── crypto/                          ServerKeyHolder (RSA-2048 keypair),
│                                     HybridCryptoService (encrypt/decrypt/hash)
├── service/                         DemoService, VirtualDevice, MeshSimulatorService,
│                                     IdempotencyService, SettlementService,
│                                     BridgeIngestionService, IngestResult
├── controller/                      ApiController, DashboardController
└── config/                          AppConfig (@EnableScheduling)

src/main/resources/
├── application.properties
└── templates/dashboard.html         the demo UI

src/test/java/com/demo/upimesh/
└── IdempotencyConcurrencyTest.java  round-trip, tamper, and concurrency tests
```

## API reference

| Method | Path                 | What it does                                        |
| ------ | -------------------- | ---------------------------------------------------- |
| GET    | `/`                  | Dashboard HTML                                        |
| GET    | `/api/server-key`    | Server's RSA public key (base64)                       |
| GET    | `/api/accounts`      | All accounts and balances                              |
| GET    | `/api/transactions`  | Last 20 transactions                                   |
| GET    | `/api/mesh/state`    | Current state of every virtual device                  |
| POST   | `/api/demo/send`     | Simulate sender phone — encrypt + inject packet         |
| POST   | `/api/mesh/gossip`   | Run one round of gossip across the mesh                |
| POST   | `/api/mesh/flush`    | Bridges with internet upload to backend (parallel)      |
| POST   | `/api/mesh/reset`    | Clear mesh + idempotency cache                          |
| POST   | `/api/bridge/ingest` | The production endpoint — real bridges POST here        |
| GET    | `/h2-console`        | Browse the in-memory DB (JDBC URL `jdbc:h2:mem:upimesh`, user `sa`, no password) |

## What's not real (and what production would swap in)

| Demo                                   | Production                                      |
| --------------------------------------- | ------------------------------------------------ |
| H2 in-memory DB                        | PostgreSQL/MySQL with replicas                    |
| `ConcurrentHashMap` idempotency cache   | Redis `SET NX EX`                                 |
| RSA keypair regenerated on startup      | Private key in an HSM/KMS                          |
| Software-simulated mesh                | Real BLE/Wi-Fi Direct between phones               |
| No auth on `/api/bridge/ingest`        | Mutual TLS / signed bridge-node certs               |
| No rate limiting                       | Per-node rate limits, velocity checks               |

## Honest limitations

The receiver has no cryptographic proof the sender's funds exist until the packet
reaches the backend — a "₹500 sent" screen offline is an IOU, not a settlement. A
malicious sender could double-spend offline by sending two packets from two devices
before either reaches the backend; whichever lands first wins, the other is
`REJECTED`. Real offline UPI (UPI Lite) avoids this with a pre-funded,
hardware-backed wallet. Call this design **mesh-routed deferred settlement**, not
real-time offline payments.
