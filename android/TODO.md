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

## Завершено в продолжении ветки

- ✅ **Combined Chats tab** — главная вкладка теперь показывает все
      переписки (1:1 и группы) с превью последнего сообщения, относительной
      меткой времени (now/5m/2h/3d) и unread-бейджем; пустое состояние
      ведёт к pairing/new-group flow.
- ✅ **Unread tracking.** `chat_messages.read` (миграция v2→v3) +
      агрегационный SQL `streamConversations()`. Чат помечается
      прочитанным при входе через `LaunchedEffect`.
- ✅ **NFC-pairing.** Manifest intent-filter на `NDEF_DISCOVERED`,
      foreground dispatch в MainActivity, `NfcPairing` парсит URI/TEXT
      записи и записывает теги; `meshlink:1:…` остаётся общим payload-ом.
- ✅ **QR camera scanner.** CameraX preview + ZXing `MultiFormatReader`
      в `QrScannerScreen`. Pairing screen теперь предлагает Scan QR как
      первичный путь.
- ✅ **Group add/remove member with rekey.** `Groups.addMembers/removeMembers`
      ротируют shared key и шлют свежий `GROUP_INVITE` всем
      оставшимся; `GroupInfoScreen` собирает переключения и применяет их
      батчем.
- ✅ **LAN TCP fallback.** Внутри `LanTransport` слушаем 43212/tcp и
      открываем outbound линки через `connectTcp`; payload'ы > 1200 байт
      дублируются в TCP, чтобы файлы и крупные envelope'ы не страдали
      на хотспотах с фильтрацией мультикаста.
- ✅ **Onboarding/theme polish.** `MeshLinkTheme` теперь оборачивает
      контент в `Surface` с явным `contentColor = onBackground`, glass
      cards оборачивают `LocalContentColor = onSurface`. Заголовок
      онбординга больше не теряется на тёмном фоне; тинты aurora и
      glass-карт стали ярче для читаемости в dynamic-color schemes.

- ✅ **Sender Keys для групп.** `crypto/SenderKeys` + таблица
      `group_sender_state` (миграция v3→v4). У каждого члена своя цепочка:
      `chain_key` ратчетится после каждого encrypt/decrypt, msg_key
      выводится через HMAC-SHA256(chain_key, "ml-msg" ‖ counter). Старый
      shared-key путь оставлен как fallback для совместимости.
- ✅ **1:1 chain ratchet.** `groups/PeerChain` хранит `peer_chain_state`
      по направлениям (send/recv); seed детерминированно выводится из
      `HKDF(session_key, "ml-root:" ‖ writer_node_id)`, поэтому обе
      стороны начинают с одной точки без хендшейка. `MeshService.sendText`
      и `decryptFromPeer` теперь идут по ratchet-пути с graceful fallback
      на legacy AES-GCM(session_key) для старых пиров.
- ✅ **Sound-pairing.** `pairing/SoundPairing` эмитит payload как 4-FSK
      (1500/2100/2700/3300Hz, 100мс/символ) с CRC-16, декодер через
      Goertzel-фильтр. UI в pairing screen: «Воспроизвести код звуком» /
      «Прослушать код собеседника» с runtime запросом RECORD_AUDIO.
- ✅ **Wi-Fi Direct auto-upgrade.** Новый mesh-тип `WIFI_HINT` несёт
      `host:lanPort:wdPort`; рассылается каждые ~60с. Получатели вызывают
      `LanTransport.connectTcp` и `WifiDirectTransport.connectTo`, чтобы
      поднять fat-pipe TCP к этому пиру; флудинг оставлен как страховка.
- ✅ **Voice notes.** `service/VoiceRecorder` пишет AAC@24 кбит/с моно;
      hold-to-record кнопка в `ChatComposer` (slide-up отменяет запись),
      файл уходит через тот же `FileTransfer.offer`-pipeline что и
      обычные attachments.
- ✅ **Карта последнего видения соседей.** PeerCard показывает
      «Последний раз: 5 мин назад» из `last_seen_ms`; lat/lon исключены
      намеренно, чтобы не раскрывать локацию через mesh.
- ✅ **LoRa-bridge.** `transport/LoraTransport` — каркас с обнаружением
      USB-устройств с known-VID (Heltec, RAK, Adafruit) и фрагментацией
      по LoRa-MTU. Реальная передача через USB-serial оставлена как
      no-op — подключается отдельная зависимость
      `usb-serial-for-android` или вендорский SDK.

## P1 — Что ещё стоит сделать

### Forward secrecy (полные ratchet'ы)
- [ ] **MLS (RFC 9420)** для групп — текущая реализация даёт forward
      secrecy для каждого отправителя, но не post-compromise: восстановить
      compromised chain ключ через DH-ratchet может только MLS / Signal.
- [ ] **DH-ratchet поверх 1:1 chain ratchet.** Каждые N сообщений
      генерировать ephemeral X25519 пару и встраивать pubkey в envelope
      для пере-derive root key — настоящий Double Ratchet вместо текущего
      symmetric-only chain.

### Стабильность транспорта
- [ ] **End-to-end перенаправление файлов на TCP.** Сейчас `WIFI_HINT`
      устанавливает back-channel, но `FileTransfer` не знает про него
      явно — сообщения уходят на все транспорты, ratx дедуплицирует.
      Можно ускорить, явно отправляя FILE_CHUNK только в TCP-линк к
      получателю когда такой есть.

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
