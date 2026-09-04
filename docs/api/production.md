# Production records API

Production rules bind a template's monotonically increasing quantity point to a device. A `GOOD`
sample creates a record only when its counter increases from the stored checkpoint; equal values do
nothing and counter resets only replace the checkpoint. This mirrors the legacy MQTT quantity
tracker without coupling iView to MQTT messages.

- `GET`, `POST /api/v1/production-rules`
- `PUT`, `DELETE /api/v1/production-rules/{id}`
- `POST /api/v1/production-rules/{id}/enable|disable`
- `GET /api/v1/production-records?page=1&page_size=20&device_id=1`
- `DELETE /api/v1/production-records/cleanup?before=...`

Each generated record retains its rule version, collection source, counter point, start/end sample
times, total quantity, and qualified quantity. Device and identical time-window uniqueness makes
replayed collection events idempotent.
