# Дизайн fallback для NAT / STUN-TURN

## Область

Этот документ определяет архитектуру fallback для сред, где прямая peer connectivity недоступна.

## Базовый уровень

- Текущая реализация ориентирована на LAN mesh.
- WebRTC использует публичный STUN сервер в конфигурации браузера.

## Предлагаемые уровни fallback

1. **Прямой P2P (предпочтительный)**
   - Прямая маршрутизация LAN.
2. **STUN-assisted traversal**
   - Обнаружение рефлексивных кандидатов.
3. **TURN relay fallback**
   - Принудительные relay кандидаты, когда прямой путь fails.
4. **Application relay (будущее расширение)**
   - Mesh relay для сигнализации и (опционально) media proxy в constrained setups.

## Предложение конфигурации

Переменные окружения (для использования endpoint конфигурации frontend):

- `MESHLINK_WEBRTC_STUN` (URLs, разделённые запятой)
- `MESHLINK_WEBRTC_TURN_URL`
- `MESHLINK_WEBRTC_TURN_USER`
- `MESHLINK_WEBRTC_TURN_PASS`
- `MESHLINK_WEBRTC_ICE_POLICY` (`all` или `relay`)

## Политика выбора кандидатов

- Начать с `iceTransportPolicy=all`.
- Если обнаружены повторные failures, переключиться на `relay` и retry.

## Замечания по безопасности

- TURN credentials не должны быть hardcoded.
- Периодически ротировать TURN credentials.
- Применять rate-limits на relay ресурсы.

## Список проверки верификации

- Звонок успешен с symmetric NAT через TURN.
- Метрики остаются видимыми в режиме relay.
- UI указывает использование relay и влияние на качество.

