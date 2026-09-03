# Device catalog API

All responses use the compatibility envelope:

```json
{"code":"200","msg":"success","data":{}}
```

## Device groups

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/device-groups` | List groups |
| GET | `/api/v1/device-groups/{id}` | Get group |
| GET | `/api/v1/device-groups/options` | Select options |
| POST | `/api/v1/device-groups` | Create group |
| PUT | `/api/v1/device-groups/{id}` | Update group |
| DELETE | `/api/v1/device-groups/{id}` | Delete an unused group |

## Templates and collection points

| Method | Path | Purpose |
|---|---|---|
| GET/POST | `/api/v1/templates` | Page templates / create template |
| GET/PUT/DELETE | `/api/v1/templates/{id}` | Template detail / update / delete |
| GET | `/api/v1/templates/options` | Template options |
| GET/POST | `/api/v1/templates/{id}/points` | List / create collection points |
| PUT/DELETE | `/api/v1/points/{id}` | Update / delete collection point |

Template and point protocol configuration is stored as JSON. Template deletion is blocked while a
device references it; deleting an unused template cascades to its collection points.

## Devices

| Method | Path | Purpose |
|---|---|---|
| GET/POST | `/api/v1/devices` | Page devices / create device |
| GET/PUT/DELETE | `/api/v1/devices/{id}` | Device detail / update / delete |
| DELETE | `/api/v1/devices/batch/{ids}` | Delete comma-separated device IDs |
| PUT | `/api/v1/devices/{id}/enable` | Enable device |
| PUT | `/api/v1/devices/{id}/disable` | Disable device |
| GET | `/api/v1/devices/check-sn` | Check serial-number uniqueness |
| GET | `/api/v1/devices/options` | Template and group options |
| POST | `/api/v1/devices/{id}/test` | Test a protocol connection |
| POST | `/api/v1/devices/{id}/diagnose` | Run configuration and network diagnosis |

Connection testing and diagnosis currently support the native JVM Modbus TCP driver. Manual
collection, realtime values, and control points remain unverified in the migration ledger.
