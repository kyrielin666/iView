# iView target architecture

## System boundary

iView is the system of record for device definitions, acquisition configuration, industrial data,
analytics, datasets, and dashboards. No Go service remains in the target runtime. Native vendor
libraries may be called through a narrowly scoped JVM adapter when a protocol vendor requires it.

```text
Devices
  -> JVM protocol drivers
  -> collector and connection supervision
  -> raw point samples + realtime cache
  -> state/production/alarm processors
  -> historical storage and OEE aggregates
  -> dataset/query engine
  -> dashboard runtime and low-code editor
```

## Dependency direction

```text
core-model <- protocol-spi <- protocol implementations
     ^              ^
     |              |
     +--------- collector --------> realtime/history
                                      |
                                      +-> alarm
                                      +-> oee

datasource adapters -> query engine -> dataset -> dashboard
                                          ^          ^
                                          |          |
                              history/oee projections+
```

Protocol implementations never depend on dashboards, datasets, authentication, or persistence.
The query engine never owns collection scheduling. OEE consumes normalized state/production events,
not vendor protocol frames.

## Target modules

| Area | Responsibility |
|---|---|
| `platform-auth` | users, roles, departments, menus, dictionaries, JWT, SSO |
| `device` | devices, groups, templates, points, control points |
| `protocol-spi` | connection/read/write/diagnostic driver contract |
| `protocol-*` | one isolated JVM implementation per industrial protocol |
| `collector` | schedules, sessions, batching, retries, reconnect and health |
| `realtime` | latest-value cache and WebSocket/SSE subscriptions |
| `history` | raw samples, retention, partitions and time-window reads |
| `alarm` | alarm rules, transitions, acknowledgements and cleanup |
| `oee` | shifts, calendars, state intervals, production, availability and OEE |
| `datasource-*` | PostgreSQL, MongoDB, CSV/file and HTTP adapters |
| `query-engine` | validation, planning, pushdown and in-memory operators |
| `dataset` | reusable query definitions, schema, preview, export and folders |
| `dashboard` | boards, folders, snapshots, templates, publish and import/export |
| `integration` | MQTT/HTTP delivery and Tiangong integration |
| `apps/server` | HTTP API, persistence assembly, security and observability |

## Persistence model

Three facts must not be collapsed into one table:

1. Point samples: immutable observed values including timestamp and quality.
2. Machine-state intervals: normalized start/end periods with rule version and provenance.
3. Aggregates: reproducible results for a shift/day/window with calculation version.

PostgreSQL is the transactional and analytical baseline. Storage optimization is deferred until
measured workloads demonstrate a need; public domain contracts must not depend on a database vendor.

## Migration rule

The legacy systems remain runnable only as acceptance references during construction. A capability
is migrated by documenting its behavior, implementing it in iView, testing normal and failure paths,
and recording release acceptance evidence outside this public repository. The target deployment
contains no legacy Go process.
