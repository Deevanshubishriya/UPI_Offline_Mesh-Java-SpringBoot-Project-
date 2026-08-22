# UPI Offline Mesh 🌐💸

A Java Spring Boot backend and visual console demonstrating offline UPI-style payments routed through a Bluetooth-style mesh network. In zero-connectivity environments, a sender's device encrypts a payment and hands it to nearby devices. The packet hops device-to-device until a "bridge" device with active internet uploads it to this backend—which decrypts, deduplicates, and settles it exactly once.

---

## 🚀 Overview & Demo Flow

The system simulates the entire lifecycle of an offline transaction:

1. **Compose a Payment:** A sender selects a receiver, amount, and PIN, then clicks **Inject into Mesh**. The backend builds a `PaymentInstruction`, hybrid-encrypts it (RSA-OAEP wraps a per-packet AES-256 key, AES-GCM encrypts the payload), wraps it in a `MeshPacket`, and hands it to the sender's virtual device.
2. **Run Gossip Round:** Every device broadcasts what it's holding to every other device, with the Time-To-Live (TTL) decrementing at each hop.
3. **Bridge Upload:** The designated "bridge" device (the only one with `hasInternet=true`) POSTs its payload to the backend via `/api/bridge/ingest`. 
4. **Ledger Update:** The backend processes the ingestion by hashing the packet, claiming it (deduplication), decrypting it, checking freshness, and finally settling the transaction. 

---

## 🛠️ Getting Started

### Prerequisites
* **JDK 17+** on your PATH (`java -version`)
* *No other installations required—the Maven wrapper downloads Maven itself on the first run.*

### Run the Application

Start the Spring Boot server:

**Mac / Linux:**
```bash
./mvnw spring-boot:run
```

**Windows:**
```cmd
mvnw.cmd spring-boot:run
```
Once running, open your browser and navigate to **`http://localhost:8080`** to access the Mesh Console.

### Run the Tests
```bash
./mvnw test
```
*Note: `IdempotencyConcurrencyTest` is the critical test here. It fires three threads at `BridgeIngestionService.ingest()` with the same packet simultaneously to assert exactly one settles.*

---

## 🏗️ Architecture

```text
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

---

## 🧠 The Three Hard Problems Solved

1. **Untrusted Intermediaries:** Solved using hybrid RSA-OAEP + AES-256-GCM. Only the server's private key can decrypt the packet. Furthermore, AES-GCM's authentication tag makes any tampering instantly detectable, throwing an exception rather than silently corrupting data (`HybridCryptoService`).
2. **Duplicate Storms:** `IdempotencyService.claim()` performs an atomic `ConcurrentHashMap.putIfAbsent` on the SHA-256 hash of the *ciphertext*. This ensures that the first caller wins, and duplicates are dropped before any heavy crypto or DB work happens. `transactions.packet_hash` acts as a unique index for defense in depth.
3. **Replay Attacks:** The encrypted payload carries a `signedAt` timestamp (rejected if >24h old) and a `nonce`. This ensures two legitimate payments of the identical amount don't collide, but a byte-identical replay attack is caught by the idempotency cache.

---

## 📂 File Map

```text
src/main/java/com/demo/upimesh/
├── UpiMeshApplication.java          # Spring Boot entrypoint
├── model/                           # Account, Transaction, MeshPacket, PaymentInstruction
├── crypto/                          # ServerKeyHolder (RSA-2048), HybridCryptoService
├── service/                         # Demo, VirtualDevice, MeshSimulator, Idempotency, Settlement, BridgeIngestion
├── controller/                      # ApiController, DashboardController
└── config/                          # AppConfig (@EnableScheduling)

src/main/resources/
├── application.properties
└── templates/dashboard.html         # The demo UI

src/test/java/com/demo/upimesh/
└── IdempotencyConcurrencyTest.java  # Round-trip, tamper, and concurrency tests
```

---

## 📡 API Reference

| Method | Path | Description |
| :--- | :--- | :--- |
| **GET** | `/` | Serves the visual Dashboard HTML |
| **GET** | `/api/server-key` | Returns the Server's RSA public key (base64) |
| **GET** | `/api/accounts` | Retrieves all accounts and current balances |
| **GET** | `/api/transactions` | Retrieves the last 20 ledger transactions |
| **GET** | `/api/mesh/state` | Returns the current state of every virtual device |
| **POST** | `/api/demo/send` | Simulates the sender phone (encrypts + injects packet) |
| **POST** | `/api/mesh/gossip` | Runs one simulated round of gossip across the local mesh |
| **POST** | `/api/mesh/flush` | Instructs bridges with internet to upload payloads to the backend |
| **POST** | `/api/mesh/reset` | Clears the mesh nodes and the idempotency cache |
| **POST** | `/api/bridge/ingest` | **The production endpoint** — Real bridges POST here |
| **GET** | `/h2-console` | Browse the in-memory DB (URL `jdbc:h2:mem:upimesh`, user `sa`, no pass) |

---

## ⚖️ Demo vs. Production

This project highlights the core logic of offline mesh routing. If scaled to a production environment, the following swaps would be required:

| Component | Demo Implementation | Production Equivalent |
| :--- | :--- | :--- |
| **Database** | H2 in-memory DB | PostgreSQL/MySQL with read-replicas |
| **Idempotency** | `ConcurrentHashMap` cache | Redis `SET NX EX` |
| **Cryptography**| RSA keypair regenerated on startup | Private key secured in an HSM / AWS KMS |
| **Networking** | Software-simulated mesh | Real BLE / Wi-Fi Direct between smartphones |
| **Security** | No auth on `/api/bridge/ingest` | Mutual TLS / signed bridge-node certificates |
| **Throttling** | No rate limiting | Per-node rate limits, velocity and abuse checks |

## ⚠️ Honest Limitations

It is important to note that the receiver has no cryptographic proof the sender's funds actually exist until the packet reaches the backend. A "₹500 sent" screen on the offline device functions as an IOU, not a hard settlement. 

A malicious sender could technically double-spend offline by sending two distinct packets from two devices before either reaches the backend; whichever lands first wins, and the other is marked `REJECTED`. Real offline UPI (like UPI Lite) bypasses this utilizing a pre-funded, hardware-backed wallet. Therefore, this specific architecture is best described as **mesh-routed deferred settlement**, rather than real-time offline payment.
