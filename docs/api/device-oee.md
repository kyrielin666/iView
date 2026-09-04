# Device OEE API

`GET /api/v1/devices/{deviceId}/oee` calculates an auditable OEE result from persisted machine-state
intervals and production records. Required query parameters are ISO-8601 `start`, `end`, and
`planned_production_ms`; optional `ideal_cycle_ms` enables performance and final OEE calculation.

Example:

```text
/api/v1/devices/12/oee?start=2026-09-04T00:00:00Z&end=2026-09-04T08:00:00Z&planned_production_ms=28800000&ideal_cycle_ms=45000
```

The response retains `window_start`, `window_end`, the `rule_versions` used by state intervals, and
the calculation version. Planned-production time is explicit for now; shifts, calendars and planned
downtime will later provide that value without changing this calculation contract.
