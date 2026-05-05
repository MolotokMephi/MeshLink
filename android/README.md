# MeshLink Android

Native Android client for MeshLink: a decentralized, **fully offline-capable**
mesh messenger that works without internet by stitching together every nearby
radio Android exposes — Bluetooth LE, IPv4 UDP-multicast on Wi-Fi LANs and
hotspots, and Wi-Fi Direct.

The mesh layer is transport-agnostic: every transport just shovels signed
envelope bytes back and forth, and the same router takes care of dedup,
TTL flooding, signature verification, anti-replay, and rate-limiting.

## Modules

```
android/app/src/main/java/team/hex/meshlink/
├── MeshLinkApp.kt              Application class; loads identity
├── crypto/
│   ├── Crypto.kt               Ed25519 sign, X25519 ECDH, AES-256-GCM (BouncyCastle)
│   └── IdentityStore.kt        Keystore-wrapped on-disk identity
├── mesh/
│   ├── Message.kt              wire envelope (kotlinx.serialization JSON)
│   ├── MeshRouter.kt           dedup + TTL + sig verify + replay/rate guards
│   └── Outbox.kt               persistent retry queue (DELIVERY_ACK based)
├── transport/
│   ├── Transport.kt            common interface
│   ├── LanTransport.kt         IPv4 UDP-multicast on 239.42.42.42:43210
│   └── WifiDirectTransport.kt  TCP framing over Wi-Fi P2P (foundations)
├── ble/
│   ├── BleConstants.kt         service / characteristic UUIDs, tunables
│   ├── Fragmentation.kt        MTU-bound chunker + Reassembler
│   └── BleTransport.kt         GATT server + GATT clients (peripheral & central)
├── files/
│   └── FileTransfer.kt         OFFER / ACCEPT / CHUNK / COMPLETE + SHA-256 + resume
├── groups/
│   └── Groups.kt               shared-key AES-GCM group chats
├── pairing/
│   └── Pairing.kt              out-of-band identity payload (`meshlink:1:…` string)
├── service/
│   └── MeshService.kt          foreground service tying everything together
├── storage/
│   ├── Db.kt                   Room v2: chat, peers, seen, outbox, groups, files
│   └── RoomSeenStore.kt        SeenStore backing for MeshRouter
└── ui/
    ├── MainActivity.kt         entry point + runtime permissions
    └── MeshNavHost.kt          Compose: peer list, group list, chat, pairing
```

## How peers find each other and exchange messages

1. **Identity.** First launch generates an Ed25519 signing keypair and an
   X25519 ECDH keypair. The private halves are stored on disk wrapped by
   an AES-256 key from the Android Keystore. Node id = first 8 bytes of
   `sha256(edPub)`.
2. **Discovery.** Every transport runs in parallel:
   - **BLE** advertises a fixed service UUID, scans for the same UUID,
     forms peripheral+central GATT connections.
   - **LAN multicast** sends/receives on 239.42.42.42:43210 — works on
     any Wi-Fi network, including offline routers and one-device
     hotspots, no configuration needed.
   - **Wi-Fi Direct** opens a TCP listener on 43211 for fat-pipe
     connections (file transfer, future media).
3. **Announce.** Every 15s the router broadcasts a signed `ANNOUNCE`
   carrying our `edPub` and `xPub`. Other nodes use those to verify
   subsequent envelopes from us and to derive ECDH session keys for
   private messages.
4. **Routing.** Every transport feeds its raw frames into the same
   router. The router:
   - drops messages it has already seen by `msg_id` (LRU + Room
     `seen_msgs` for cross-restart dedup)
   - drops messages whose timestamp is more than ±5 minutes off (replay)
   - drops repeated `(senderId, nonce)` pairs (replay)
   - drops messages whose `relay_path` already contains us (loop)
   - drops messages whose sender exceeds a per-source token bucket
     (16 msg/s burst 64) (anti-flood)
   - verifies the Ed25519 signature using the announced `edPub`
   - if the message is for us, surfaces it on `appInbox`
   - if `ttl > 1`, decrements TTL, appends our id, and re-broadcasts on
     **every** transport — flooding mesh.
5. **1:1 messages.** Encrypted with `aes-gcm(sha256(ECDH(myXPriv, peerXPub)))`,
   signed at the envelope, retried by the persistent Outbox until the
   recipient sends a `DELIVERY_ACK`.
6. **Group messages.** Encrypted with a shared 256-bit AES-GCM key.
   Group invites are issued as 1:1 ciphertexts; group messages are
   broadcast (any peer relays, only members can decrypt).
7. **File transfer.** Sender SHA-256-hashes the file, sends `FILE_OFFER`,
   awaits `FILE_ACCEPT`, streams `FILE_CHUNK`s (32 KiB), finishes with
   `FILE_COMPLETE`. Receiver verifies the digest. Resumable: on restart,
   `FILE_ACCEPT.fromOffset` skips already-received bytes.
8. **Pairing.** Devices can trust each other out-of-band: open the
   pairing screen, copy your code (`meshlink:1:<base64>`), have the
   other side paste it. No mesh, no internet, no third party.

## Building

The Gradle wrapper is checked in. Build the debug APK from the `android`
directory:

```bash
cd android
./gradlew :app:assembleDebug
```

GitHub Actions runs `assembleDebug`, `lintDebug`, and `testDebugUnitTest`
on every push that touches `android/`. See `.github/workflows/android.yml`.

## See also

`android/TODO.md` — what's left for production-grade behavior (proper
QR matrix renderer, Wi-Fi Direct discovery state machine, MLS-style
forward-secret group keys, etc.).
