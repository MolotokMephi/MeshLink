# MeshLink Android — оставшиеся задачи

С момента MVP закрыты все P0/P1 пункты прошлой версии TODO. Текущий
скоп ниже — то, что нужно для production-grade офлайн-мессенджера.

## Завершено в этой ветке

- ✅ Multi-transport архитектура (`Transport` интерфейс, BLE + LAN
  multicast + Wi-Fi Direct foundations).
- ✅ MTU-aware фрагментация per-link, очередь записи с retry,
  cap=5 одновременных GATT-линков, exponential backoff на reconnect.
- ✅ Persistent seen-cache в Room (`seen_msgs`), миграция v1→v2.
- ✅ Outbox + ack/retry с exponential backoff (Room `outbox`).
- ✅ Anti-replay: timestamp window ±5min, nonce window per-sender (64).
- ✅ Loop-detect через relay_path; per-sender token bucket
  (rate 16 msg/s, burst 64).
- ✅ Android Keystore-wrapping приватных ключей (legacy v1 layout
  мигрируется при первом старте).
- ✅ File transfer (OFFER / ACCEPT / CHUNK / COMPLETE + SHA-256 + resume).
- ✅ Group chats (shared-key AES-GCM, GROUP_INVITE через 1:1).
- ✅ Out-of-band peer pairing (`meshlink:1:<base64>` строка).
- ✅ Read receipts (encrypted) + typing indicator messages.
- ✅ Compose UI: peer list, group list, chat (с delivery-state ✓✓/read),
  pairing screen, attach-file через ActivityResult.
- ✅ Unit tests: Fragmentation roundtrip, Crypto sign/verify/ECDH,
  MeshMessage canonical signing across relay mutations.
- ✅ GitHub Actions CI: assembleDebug + lintDebug + testDebugUnitTest +
  upload APK + lint report.

## P1 — критичные доработки до прод-релиза

### Wi-Fi Direct follow-ups
- [ ] **BroadcastReceiver state machine.** В `WifiDirectTransport.start()`
      сейчас только TCP-listener; нет регистрации
      `WIFI_P2P_PEERS_CHANGED_ACTION` / `WIFI_P2P_CONNECTION_CHANGED_ACTION`,
      нет авто-discovery через `discoverPeers()`/`requestPeers()`,
      нет авто-connect. Нужен полноценный конечный автомат
      idle → discovering → connecting → connected → group-owner | client.
- [ ] **Auto-upgrade с BLE на Wi-Fi Direct** для больших передач: при
      получении FILE_OFFER предложить upgrade-канал, согласовать роли
      group-owner/client, передать чанки через TCP.
- [ ] **Wi-Fi P2P-permission UX:** на API 33+ нужен runtime-grant
      `NEARBY_WIFI_DEVICES`, на 30- — fine location.

### QR pairing follow-ups
- [ ] **Реальный QR-matrix encoder.** В `pairing/Pairing.kt::QrEncoder`
      сейчас плейсхолдер: версия выбирается, но Reed-Solomon, маски и
      format-info не реализованы. Текстовый payload (`meshlink:1:…`) уже
      работает копи-пастом. Добавить полноценный encoder (≈400 строк
      математики) или мини-зависимость на `core/qrcode-kotlin`.
- [ ] **Сканер QR.** Сейчас вход — только paste. Добавить
      CameraX + ML Kit Barcode Scanning (или ZXing Core) и интент.
- [ ] **NFC-pairing** через `Ndef` для устройств с NFC: запись
      `meshlink:1:…` строки в payload, чтение через `NDEF_DISCOVERED`.
- [ ] **Sound-pairing** (gg-wave / chirp.io) как fallback там, где нет
      ни камеры, ни NFC, ни общей сети.

### Forward secrecy для групп
- [ ] **MLS / Signal Sender Keys.** Текущая `groups/Groups.kt` — один
      статический AES-ключ на группу. При компрометации одного устройства
      раскрываются все прошлые/будущие сообщения. Внедрить либо
      Signal Sender Keys (per-sender ratchet) либо MLS (RFC 9420);
      первый вариант проще, второй — стандартный.
- [ ] **Add/remove member** с rekey: сейчас `members_csv` хранится, но
      операций по добавлению/удалению с ротацией ключа нет.

### Стабильность транспорта
- [ ] **BLE: handle WRITE_TYPE_NO_RESPONSE.** Для chat-сообщений (не
      файлов) можно использовать write-without-response — в разы быстрее
      и не блокирует очередь подтверждениями. Сейчас всегда DEFAULT.
- [ ] **BLE: subscribe-driven keepalive.** Если сабскрайбер не отзывается
      ping/pong > N секунд, дропать линк, запускать backoff.
- [ ] **LAN: TCP fallback внутри LanTransport** для крупных payload'ов
      (когда multicast pipe недостаточен и MTU < 1400 — на mobile-AP
      это часто).
- [ ] **LAN: auto-rejoin multicast group** на change-of-network
      (`ConnectivityManager.NetworkCallback`).
- [ ] **Wi-Fi Direct: keepalive + reconnect.** Сейчас `FrameLink` молча
      падает при разрыве — нужна реконнект-стратегия с heartbeat.

### Безопасность
- [ ] **Identity recovery.** Если Keystore-ключ утерян (factory reset
      без бэкапа), идентичность теряется навсегда. Добавить экспорт
      mnemonic-фразы по BIP39 через KDF из identity.
- [ ] **Per-recipient session ratchet** (Double Ratchet) вместо чистого
      ECDH — даст forward secrecy для 1:1 чатов.
- [ ] **Trust-on-first-use markers.** Сейчас `peers.trusted = false` для
      announce-обнаруженных, `true` для pairing. UI должен явно различать
      «случайный сосед» и «доверенный контакт» и предупреждать о
      смене edPub под тем же display name.
- [ ] **Rate-limit storms.** Вместо silent-drop при превышении
      bucket-rate — временный ban (на 30s/5min), как в Python-ядре
      `core/security`.
- [ ] **Outbox: подпись retry-envelope с актуальным timestamp.** Сейчас
      retry шлёт исходный envelope с прежним timestamp; через 5 минут он
      будет дропаться по anti-replay window. Нужно при retry
      переподписывать envelope с новым timestamp+nonce.

### UX
- [ ] **Onboarding-флоу:** объяснить, зачем разрешения, провести через
      battery-optimization whitelist на не-Pixel устройствах.
- [ ] **Settings screen:** display name, чистка истории, экспорт identity.
- [ ] **Live-link индикатор** в peer-list: прямой BLE/LAN/WD vs «через
      mesh-relay».
- [ ] **Push-уведомления** на новые сообщения (через тот же
      foreground-сервис, не FCM).
- [ ] **Поиск по чатам** (FTS на `chat_messages.body`).
- [ ] **Material You + dynamic colors** на API 31+.
- [ ] **Полноценный flow выбора собеседников** для group create
      (сейчас в UI нет экрана создания группы — только список существующих).

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
  блокировки экрана. Без runtime запроса
  `requestIgnoreBatteryOptimizations` mesh не выживет ночь.
- **Multicast filtering** на старых Android-роутерах. Hotspot Pixel
  фильтрует мультикаст между клиентами по умолчанию — нужен fallback на
  255.255.255.255 broadcast.
- **Wi-Fi Direct + STA одновременно.** На большинстве устройств Wi-Fi
  Direct отключает обычное Wi-Fi на время сессии. UX должен это
  объяснять.
