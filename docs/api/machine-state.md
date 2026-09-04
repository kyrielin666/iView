# Machine-state rules API

Machine-state rules normalize a selected template collection point into a business status and OEE
runtime state. Raw telemetry remains immutable in the telemetry store; the state subsystem keeps a
separate current-state projection and state intervals.

## Rules

- `GET /api/v1/machine-state-rules`
- `POST /api/v1/machine-state-rules`
- `GET`, `PUT`, `DELETE /api/v1/machine-state-rules/{id}`
- `POST /api/v1/machine-state-rules/{id}/enable`
- `POST /api/v1/machine-state-rules/{id}/disable`

Example payload:

```json
{
  "template_id": 1,
  "point_id": 8,
  "standard_mapping": {"1": "normal", "0": "fault"},
  "runtime_mapping": {"1": "RUNNING", "0": "FAULT"},
  "default_status": "unknown",
  "default_runtime_state": "UNKNOWN",
  "enabled": 1
}
```

The point value is normalized as trimmed lowercase text before mapping. Rule updates increment the
persisted rule version. Every state interval retains that version, allowing later OEE calculations
to identify the rule that produced their time window.

## Device state

- `GET /api/v1/devices/{deviceId}/machine-state/current`
- `GET /api/v1/devices/{deviceId}/machine-state/intervals?start=...&end=...`

Only `GOOD` point values are standardized. A changed runtime state closes the preceding interval
and creates the next one atomically with the current-state projection.
