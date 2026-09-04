# Outbound push API

All endpoints use the iView compatibility envelope. Push configuration fields preserve the legacy
names `timeout`, `retry_count`, `retry_delay`, and integer `enabled` values.

| Method | Path | Purpose |
|---|---|---|
| GET/POST | `/api/v1/push-configs` | Page or create push configurations |
| GET/PUT/DELETE | `/api/v1/push-configs/{id}` | Detail, update, or delete one configuration |
| DELETE | `/api/v1/push-configs/batch/{ids}` | Delete comma-separated configuration IDs |
| POST | `/api/v1/push-configs/{id}/test` | Test HTTP or MQTT connectivity |
| POST | `/api/v1/push-configs/{id}/enable` | Enable delivery |
| POST | `/api/v1/push-configs/{id}/disable` | Disable delivery and close its cached adapter |
| GET | `/api/v1/push-configs/types` | Implemented push types (`http`, `mqtt`) |
| GET | `/api/v1/push-logs` | Page logs; filter by `config_id`, `device_sn`, or `status` |
| GET | `/api/v1/push/status` | Bounded asynchronous queue and last-error status |

HTTP configuration accepts `url`, `method` (`POST`, `PUT`, or `PATCH`), `content_type`, and a
`headers` object. MQTT accepts `broker`, `topic`, `client_id`, `username`, `password`, `qos`, and
`clean_session`. Topics support `{device_sn}`, `{device_name}`, `{template_id}`, and
`{template_code}` placeholders.

Successful collection persists telemetry first, then enqueues a legacy-compatible payload for
every enabled target. A failed attempt creates a status `2` retry log; final success is `1` and
final failure is `0`. `retry_count` is the number of retries after the initial attempt.
