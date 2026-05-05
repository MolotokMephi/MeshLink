# MeshLink Android — оставшиеся задачи

Эта ветка (`claude/android-bluetooth-mesh-YpZU6`) содержит **MVP-скелет**
Android-клиента: identity-крипто, mesh-роутер с TTL/dedup/relay,
BLE-транспорт (GATT-сервер + клиент + advertise/scan + фрагментация),
foreground-сервис, Room-хранилище и Compose UI с двумя экранами.

Чего не успел / что нужно доделать перед тем, как ставить на устройство и
заявлять «полноценный mesh»:

## P0 — прежде чем впервые компилировать

- [ ] **Сгенерировать Gradle Wrapper.** В репо нет `gradlew`, `gradlew.bat` и
      `gradle/wrapper/gradle-wrapper.{jar,properties}`. Сделать один раз:
      `cd android && gradle wrapper --gradle-version 8.7`. Закоммитить
      сгенерированные файлы.
- [ ] **Проверить сборку:** `./gradlew :app:assembleDebug`. Скорее всего
      придётся обновить версии Compose BOM / KSP под ту версию AGP, что
      установлена локально.
- [ ] **Иконка приложения.** Сейчас `android:icon`/`roundIcon` убраны из
      манифеста (используется системная). Для релиза добавить
      `mipmap-*/ic_launcher.*` (можно сгенерить через Image Asset Studio).
- [ ] **Минимальные unit-тесты** для `Fragmentation` (split↔Reassembler) и
      `MeshMessage.verifyWith` — это самые «легко тестируемые в JVM» куски,
      они дают быстрый регресс-набор.

## P1 — функциональные пробелы относительно Python-ядра

### Транспорт
- [ ] **Переподписки и keep-alive.** Сейчас если outbound-линк отвалился, мы
      опираемся на повторный scan-результат. Добавить экспоненциальный
      reconnect и periodic ping (`MsgType.PING/PONG`) для таймаут-детекта.
- [ ] **Лимит одновременных GATT-соединений.** На большинстве Android-стеков
      одновременно открыто 4–7 GATT-соединений. Нужен LRU-эвиктор по
      `lastSeenMs`/RSSI.
- [ ] **MTU-aware chunk size.** В `BleTransport.broadcast` сейчас захардкожен
      `LARGE_CHUNK_PAYLOAD = 244`. Запрашиваем MTU 517, но реально
      используемый размер надо подхватывать из `onMtuChanged` и хранить
      per-link.
- [ ] **Контроль скорости.** Без backpressure GATT-стек вендоров (особенно
      Samsung/Xiaomi) начинает дропать write-request'ы. Очередь
      `OutboundLink.writeQueue` уже сериализована, но нужно добавить
      retry на `onCharacteristicWrite(status != GATT_SUCCESS)`.

### Mesh-протокол
- [ ] **Outbox + ack/retry** (как `core/messaging.py`): сообщение считается
      доставленным только после `DELIVERY_ACK` от получателя; до этого —
      ретраим с backoff. Сейчас текстовые сообщения пишутся в БД сразу как
      «отправлено», что неправда при отсутствии маршрута.
- [ ] **Mesh-замыкания и счётчики.** Добавить `loop-detect` через
      `relay_path` (если наш id уже там — не релеить).
- [ ] **Антифлуд / rate-limit per sender** (есть в Python-ядре —
      перенести). Иначе один скомпрометированный узел положит сеть.
- [ ] **Persistent seen-cache.** Сейчас `seen` сбрасывается при
      перезапуске сервиса; после краша возможно повторное всплытие старых
      сообщений. Сохранять последние N msg_id в Room.

### Фичи поверх mesh
- [ ] **Передача файлов** (порт `core/file_transfer.py`): чанки + SHA-256 +
      resume. По BLE это будет медленно (десятки КБ/с), но рабочее.
- [ ] **Wi-Fi Direct / Hotspot транспорт** как альтернатива BLE для
      больших полезных нагрузок и звонков. Требует своего `Transport`-
      адаптера и multiplexing'a в `MeshRouter` (роутер уже
      transport-agnostic — можно добавить вторую подписку на `outgoing`).
- [ ] **Групповые чаты.** Сейчас протокол поддерживает broadcast
      (`recipientId == null`), но UI оперирует только 1:1. Добавить
      понятие комнаты + AES-GCM с групповым ключом.
- [ ] **Seed-pairing UI.** В Python-ядре есть pairing через короткий
      seed (короткий код в воздухе) — перенести как QR-код экран.
- [ ] **Read receipts / typing.** `MsgType.READ_RECEIPT`, `MsgType.TYPING`
      определены в Python-ядре, в Kotlin-mesh не реализованы.

### Хранилище и состояние
- [ ] **Миграции Room.** Сейчас `version = 1` без миграций — при любом
      изменении схемы вылетит при первом обновлении.
- [ ] **DataStore для display name** вместо SharedPreferences (мелочь).
- [ ] **Truncation/eviction** старых сообщений (в Python-ядре есть
      `CHAT_DB_MAX_MB` / `CHAT_DB_MAX_ROWS`).

### Безопасность
- [ ] **Android Keystore.** Сейчас Ed25519/X25519 приватные ключи лежат в
      SharedPreferences в base64 — допустимо для MVP, но для прод нужно
      хранить master-ключ в Keystore и шифровать им identity-blob.
- [ ] **Подписать ANNOUNCE с anti-replay.** В тело announce включить
      `nonce` и/или временную метку с допуском, чтобы нельзя было
      воспроизвести старый announce.
- [ ] **Blacklist/ban.** Соответствует rate-limit задаче выше.

### UX
- [ ] **Экран онбординга:** запросить разрешения дружелюбно, объяснить
      зачем нужен Bluetooth в фоне; на Android 12+ — флоу
      «не выводить из энергосбережения».
- [ ] **Имя устройства** настраивается, но в UI нет экрана настроек.
- [ ] **Индикатор живых линков.** В UI peer-list нет различия между
      «слышали 30 секунд назад через mesh-relay» и «прямой BLE-линк».
- [ ] **Тёмная/светлая темы + Material You.**

## P2 — медиа и звонки

- [ ] WebRTC через BLE — нереалистично (слишком узкий канал). Если хочется
      звонки — поднимать поверх Wi-Fi Direct и делать сигналинг через
      BLE-mesh.

## Известные риски / подводные камни вендоров

- **Samsung/Xiaomi BLE-стеки** агрессивно дропают одновременную роль
  peripheral+central. На старых устройствах (Android 8/9) BLE-advertising
  может не запуститься без `BLUETOOTH_PRIVILEGED`.
- **Doze + App Standby.** Без вайтлиста производителя сервис могут
  убивать через 5–10 минут после блокировки экрана. Нужен
  `BatteryOptimizations` экран и опционально `requestIgnoreBatteryOptimizations`.
- **Android 14 (API 34) FGS-types:** мы используем `connectedDevice` —
  работает, но с API 34 требуется `FOREGROUND_SERVICE_CONNECTED_DEVICE`
  permission (уже добавлен в манифест).
- **maxConnections в peripheral-роли** не управляется приложением; на
  Pixel это обычно 4–7, но протокол должен корректно деградировать.
