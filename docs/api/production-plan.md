# Production plan API

Shifts define recurring local-time production windows. Planned downtime records can be global or
device-specific. OEE derives planned production time as merged shift windows minus overlapping
planned downtime, including shifts that cross midnight.

- `GET`, `POST /api/v1/shifts`
- `PUT`, `DELETE /api/v1/shifts/{id}`
- `GET /api/v1/planned-downtimes?device_id=1&start=...&end=...`
- `POST /api/v1/planned-downtimes`
- `DELETE /api/v1/planned-downtimes/{id}`

`GET /api/v1/devices/{id}/oee` now makes `planned_production_ms` optional. When omitted, it derives
the planned duration from this schedule. Passing it remains supported for an explicit what-if run.
