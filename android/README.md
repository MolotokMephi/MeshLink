# MeshLink Android

Native Android client for MeshLink with a BLE-based peer-to-peer mesh
transport. The mesh layer is transport-agnostic and mirrors the protocol
implemented by the Python core (`core/messaging.py`): signed envelopes,
TTL-based flooding, msg-id dedup, relay path tracking.

## Module map

```
android/app/src/main/java/team/hex/meshlink/
├── MeshLinkApp.kt              — Application class; loads/creates identity keys
├── crypto/
│   ├── Crypto.kt               — Ed25519 sign/verify, X25519 ECDH, AES-256-GCM
│   └── IdentityStore.kt        — persists Ed25519+X25519 keypair
├── mesh/
│   ├── Message.kt              — wire format + JSON (kotlinx.serialization)
│   └── MeshRouter.kt           — dedup, TTL, signature verify, flooding relay
├── ble/
│   ├── BleConstants.kt         — service / characteristic UUIDs
│   ├── Fragmentation.kt        — MTU-bound chunker + Reassembler
│   └── BleTransport.kt         — GATT server + scanner + outbound GATT clients
├── service/
│   └── MeshService.kt          — foreground service tying transport↔router↔db
├── storage/
│   └── Db.kt                   — Room: chat_messages, peers
└── ui/
    ├── MainActivity.kt         — entry point + runtime permissions
    └── MeshNavHost.kt          — Compose: peer list + chat screen
```

## How peer discovery & messaging work

1. On startup the app loads (or generates) an `Ed25519` signing key and an
   `X25519` ECDH key. Node id = first 8 bytes of `sha256(edPub)`.
2. `MeshService` starts BLE advertising the service UUID and scans for the
   same UUID. Every match triggers an outbound GATT connection; meanwhile
   inbound centrals connect to our local GATT server.
3. Every 15s we broadcast a signed `ANNOUNCE` frame containing our `edPub`
   and `xPub`. Peers store these and use `edPub` to verify subsequent
   messages from us.
4. Application messages are AES-256-GCM ciphertexts keyed by
   `sha256(ECDH(myXPriv, peerXPub))`. They're wrapped in a signed mesh
   envelope and broadcast. Any peer in range relays — TTL decremented,
   `relay_path` appended — until TTL hits 1 or recipient delivers.
5. `MeshRouter` dedups by `msg_id`, verifies signatures with the sender's
   announced `edPub`, and surfaces messages addressed to us via
   `appInbox`.

## Building

This directory is a stand-alone Gradle project. The Gradle wrapper is **not**
checked in (no `gradle/wrapper/`, no `gradlew`); generate it once with a
locally-installed Gradle 8.7+:

```bash
cd android
gradle wrapper --gradle-version 8.7
./gradlew :app:assembleDebug
```

Or open `android/` in Android Studio Hedgehog (or newer) and let the IDE
generate the wrapper on first sync.

## See also

`android/TODO.md` — what's left for full feature parity with the Python
core (file transfer over BLE, Wi-Fi-Direct fallback, Keystore-protected
keys, group chats, etc.).
