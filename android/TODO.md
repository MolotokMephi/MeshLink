# MeshLink Android — оставшиеся задачи

С момента MVP закрыты все P0/P1 пункты прошлой версии TODO. Текущий
скоп ниже — то, что нужно для production-grade офлайн-мессенджера.

## Завершено в этой ветке

- ✅ Multi-transport архитектура (`Transport` интерфейс, BLE + LAN
  multicast + Wi-Fi Direct foundations).
- ✅ MTU-aware фрагментация per-link, очередь записи с retry,
  cap=5 одновременных GATT-линков, exponential backoff на reconnect.
- ✅ Persistent seen-cache в Room (`seen_msgs`), миграция v1→v2.
- ✅ Outbox + ack/retry с exponential backoff (Room `outbox`),
  re-sign retry envelope с актуальным timestamp+nonce.
- ✅ Anti-replay: timestamp window ±5min, nonce window per-sender (64).
- ✅ Loop-detect через relay_path; per-sender token bucket
  (rate 16 msg/s, burst 64); временный ban при систематическом превышении.
- ✅ Android Keystore-wrapping приватных ключей (legacy v1 layout
  мигрируется при первом старте).
- ✅ File transfer (OFFER / ACCEPT / CHUNK / COMPLETE + SHA-256 + resume),
  out-of-order chunk write через RandomAccessFile по offset.
- ✅ Group chats (shared-key AES-GCM, GROUP_INVITE через 1:1) + UI flow
  создания группы с выбором участников.
- ✅ Out-of-band peer pairing (`meshlink:1:<base64>` строка) + полноценный
  QR matrix encoder (Reed-Solomon, mask selection, format & version info,
  versions 1–10 byte mode EC level L).
- ✅ Read receipts (encrypted) + typing indicator messages.
- ✅ Liquid-glass Compose UI: aurora-gradient background, frosted-panel
  primitives, Material You dynamic-color palette на API 31+.
  Экраны: peer list с live-link badges (direct/relay/no-route),
  group list, chat (delivery state ✓✓/read), pairing с QR,
  settings, onboarding с battery-whitelist prompt, group create,
  chat search.
- ✅ Push-уведомления на новые входящие сообщения через тот же
  foreground-сервис; deep-link обратно в нужный чат.
- ✅ Identity recovery через 64-байтовый recovery code (edPriv ‖ xPriv +
  4-байтный SHA-256 checksum) с экспортом из Settings.
- ✅ Mesh-graph: per-edge timestamps, BFS shortest-path next-hop hint,
  TTL trim для известных получателей. Граф наполняется и из direct-frames,
  и из `relay_path` каждого верифицированного envelope.
- ✅ TOFU mismatch detection: перенос announce с тем же node id, но
  другим Ed25519 ключом отзывает trust и поднимает уведомление.
- ✅ Wi-Fi Direct полноценный state machine: BroadcastReceiver на
  `WIFI_P2P_*_CHANGED_ACTION`, `discoverPeers`/`requestPeers`/`connect`,
  TCP framing с writer-mutex и heartbeat-keepalive.
- ✅ BLE write-without-response для коротких сообщений (chat / typing /
  ANNOUNCE / read receipt), serialize notify-broadcast, idle-link
  prune через `lastActivityMs`.
- ✅ LAN: auto-rejoin multicast group через `ConnectivityManager.NetworkCallback`,
  одновременный broadcast на `255.255.255.255` для Wi-Fi-AP с фильтром мультикаст.
- ✅ Unit tests: Fragmentation roundtrip, Crypto sign/verify/ECDH,
  MeshMessage canonical signing across relay/ttl mutations,
  MeshGraph BFS, MnemonicBackup roundtrip + bad checksum, QR finder
  pattern correctness.
- ✅ GitHub Actions CI: assembleDebug + lintDebug + testDebugUnitTest +
  upload APK + lint report.

## P1 — Что ещё стоит сделать

### Forward secrecy
- [ ] **MLS / Signal Sender Keys для групп.** Сейчас `groups/Groups.kt` — один
      статический AES-ключ на группу. При компрометации одного устройства
      раскрываются все прошлые/будущие сообщения. Внедрить либо
      Signal Sender Keys (per-sender ratchet) либо MLS (RFC 9420);
      первый вариант проще, второй — стандартный.
- [ ] **Add/remove member** с rekey: сейчас `members_csv` хранится, но
      операций по добавлению/удалению с ротацией ключа нет.
- [ ] **Per-recipient session ratchet (Double Ratchet)** вместо чистого
      ECDH — даст forward secrecy для 1:1 чатов.

### Discovery / pairing UX
- [ ] **Сканер QR.** Сейчас вход — только paste. Добавить
      CameraX + ML Kit Barcode Scanning (или ZXing Core) и интент.
- [ ] **NFC-pairing** через `Ndef` для устройств с NFC: запись
      `meshlink:1:…` строки в payload, чтение через `NDEF_DISCOVERED`.
- [ ] **Sound-pairing** (gg-wave / chirp.io) как fallback там, где нет
      ни камеры, ни NFC, ни общей сети.

### Стабильность транспорта
- [ ] **Auto-upgrade с BLE на Wi-Fi Direct** для больших передач: при
      получении FILE_OFFER предложить upgrade-канал, согласовать роли
      group-owner/client, передать чанки через TCP.
- [ ] **LAN: TCP fallback внутри LanTransport** для крупных payload'ов
      (когда multicast pipe недостаточен и MTU < 1400 — на mobile-AP
      это часто).

## P2 — приятные мелочи

- [ ] **Voice notes / push-to-talk** через Wi-Fi Direct (Opus в
      контейнере OGG, ~24 кбит/с).
- [ ] **Карта последнего видения соседей** (lat/lon в локальном
      состоянии устройства, **не** в mesh-payload).
- [ ] **Bridge к LoRa-модулю** через USB-OTG / Bluetooth-classic для
      километровых дальностей. Архитектурно это просто ещё один
      `Transport`-имплементер.
- [ ] **Веб-интерфейс по WebSocket** на самом телефоне для
      управления с десктопа без интернета (через тот же Wi-Fi).

## Известные риски

- **Vendor BLE quirks.** Samsung/Xiaomi агрессивно режут одновременные
  peripheral+central; на API 31+ при отсутствии `BLUETOOTH_PRIVILEGED`
  advertising может молча не запуститься. Нужно вендор-матричное
  тестирование.
- **Doze + App Standby** на не-Pixel убивают FGS через 5–10 минут после
  блокировки экрана. Onboarding теперь приводит пользователя к
  `requestIgnoreBatteryOptimizations`, но юзер всё ещё может отказаться.
- **Wi-Fi Direct + STA одновременно.** На большинстве устройств Wi-Fi
  Direct отключает обычное Wi-Fi на время сессии. UX должен это
  объяснять.
