# Device alarms API

This vertical slice preserves the legacy availability-alarm behavior: a device can have at most one
active alarm. A collection batch with `TIMEOUT`, `OFFLINE`, `BAD_CONFIGURATION`, `BAD_RESPONSE`,
or `OUT_OF_RANGE` creates it; a later batch whose points have no such quality automatically marks
it recovered. Existing active records are never overwritten.

## Classification rules

- `GET /api/v1/device-alarm-classification-rules`
- `GET /api/v1/device-alarm-classification-rules/{id}`
- `POST /api/v1/device-alarm-classification-rules`
- `PUT /api/v1/device-alarm-classification-rules/{id}`
- `POST /api/v1/device-alarm-classification-rules/{id}/enable`
- `POST /api/v1/device-alarm-classification-rules/{id}/disable`
- `DELETE /api/v1/device-alarm-classification-rules/{id}`

Create/update payload:

```json
{"pattern":".*离线.*","category":1,"priority":0,"enabled":1}
```

`pattern` is a Java regular expression. Enabled rules are evaluated by ascending `priority`, then
ascending ID; the first match supplies the immutable record's `alarm_category`.

## Alarm records

`GET /api/v1/device-alarms?page=1&page_size=20&device_id=12&status=ACTIVE` returns a page of
immutable abnormal records with automatic `ACTIVE` or `RECOVERED` state. The stored event window
is `abnormal_at` to `recovered_at`, and retains the content and classification calculated at the
time the alarm opened.

Alarm listing is constrained by the authenticated user's device scope. `POST
/api/v1/device-alarms/{id}/acknowledge` records a manual acknowledgement without changing the
automatic `ACTIVE`/`RECOVERED` state. Its optional payload is `{"note":"现场已检查"}`; the server
records `acknowledged_at`, the authenticated username in `acknowledged_by`, and the note. Repeated
acknowledgement is idempotent and does not overwrite the original operator or note. A caller cannot
acknowledge an alarm outside their device scope.

`DELETE /api/v1/device-alarms/cleanup?before=2026-01-01T00:00:00Z` deletes historical records
whose abnormal time predates the required UTC cutoff. Callers must choose the retention cutoff;
no browser action performs cleanup automatically.
