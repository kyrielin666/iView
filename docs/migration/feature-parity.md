# Legacy feature parity ledger

Status values:

- `NOT_STARTED`: behavior has been inventoried but no iView implementation exists.
- `IN_PROGRESS`: contracts or implementation exist but parity has not been accepted.
- `VERIFIED`: automated and/or hardware acceptance evidence proves parity.

Nothing may be marked `VERIFIED` based only on code presence.

## Platform and SCADA

| Capability | Legacy reference | Status | Acceptance evidence |
|---|---|---|---|
| Login, refresh token, logout, current user | `scada-preview`, `my-scada-next` | NOT_STARTED | — |
| SSO signed login | `scada-preview` | NOT_STARTED | — |
| Users, roles, permissions | `scada-preview`, `my-scada-next` | NOT_STARTED | — |
| Departments, menus and route permissions | `scada-preview`, `my-scada-next` | NOT_STARTED | — |
| Dictionaries and dictionary items | `scada-preview`, `my-scada-next` | NOT_STARTED | — |
| Devices and device groups | `scada-preview`, `my-scada-next` | IN_PROGRESS | JDBC/Flyway persistence and compatible CRUD API; integration flow in `DeviceCatalogApiTest` |
| Device templates and collection points | `scada-preview`, `my-scada-next` | IN_PROGRESS | Template/point persistence and CRUD API; integration flow in `DeviceCatalogApiTest` |
| Control points, execution and control logs | `scada-preview`, `my-scada-next` | IN_PROGRESS | Persistent CRUD, single/multi-target session-aware writes, success/failure audit API and UI; integration tests cover multi-target success and read-only failure; hardware acceptance remains |
| Connection test and device diagnosis | `scada-preview` | IN_PROGRESS | Modbus TCP test/diagnose API and Spring integration; API integration test |
| Collector scheduling and manual collection | `scada-preview` | IN_PROGRESS | Session-aware collection engine, optional scheduler, manual device/single-point APIs and integration test |
| Connection reuse, timeout and reconnect | `scada-preview` | IN_PROGRESS | Device session pool, config-change invalidation, timeout and reconnect tests; long-running soak test remains |
| Realtime values and device statistics | `scada-preview` | IN_PROGRESS | Immutable JDBC samples plus latest/realtime, history and device-statistics APIs |
| Device process/plugin monitoring | `scada-preview` | IN_PROGRESS | JVM session-pool status API replaces external plugin-process monitoring; health history remains |
| MQTT and HTTP push configuration | `scada-preview` | IN_PROGRESS | Persistent compatible CRUD/test API, HTTP and MQTT adapters, topic placeholders, collection-triggered async delivery and management UI; external broker acceptance remains |
| Push logs, retry and enable/disable | `scada-preview` | IN_PROGRESS | Configurable timeout/retry delay, retry/final audit persistence, enable/disable, bounded queue status; deterministic retry and local HTTP integration tests |
| Alarm rules, records and cleanup | `dashboard-service`, `scada-preview` | IN_PROGRESS | Device abnormal/recovery deduplication, ordered regex classification, persistent records, explicit cleanup API and management UI; production retention schedule and device acceptance remain |
| Machine-state rules and standardization | `dashboard-service` | IN_PROGRESS | Point-value mapping, default fallback, versioned rules, current-state projection and persisted OEE-ready intervals; legacy message-source extraction and production acceptance remain |
| Runtime periods and daily accumulation | `dashboard-service` | IN_PROGRESS | OEE interval calculator foundation |
| Production records and quantity rules | `dashboard-service` | IN_PROGRESS | Versioned quantity-point binding, persistent per-device checkpoints, rising-counter deltas, idempotent records and cleanup API; qualified-count point and production acceptance remain |
| Shifts, calendars and planned downtime | product requirement | IN_PROGRESS | Versioned recurring shift and global/device downtime persistence; cross-midnight and downtime subtraction tests; production calendar acceptance remains |
| Availability/utilization and OEE | product requirement | IN_PROGRESS | Deterministic calculator plus device/time-window API reading persisted state intervals and production records; shift/calendar acceptance remains |
| Tiangong config, sync and last-will | `scada-preview`, `my-scada-next` | NOT_STARTED | — |
| Resource/system monitoring | `scada-preview`, `my-scada-next` | NOT_STARTED | — |
| Backup and restore | `scada-preview` | NOT_STARTED | — |
| OpenAPI device/template import | `scada-preview` | NOT_STARTED | — |

## Industrial protocols

| Protocol | Legacy reference | Status | Required acceptance |
|---|---|---|---|
| Modbus TCP | `plugins/modbus_tcp` | IN_PROGRESS | JVM frame read/write, endian, scaling, merged batch read, timeout and reconnect tests; hardware acceptance remains |
| Modbus RTU | `plugins/modbus_rtu` | NOT_STARTED | serial framing, CRC, read/write, timeout |
| Mitsubishi MC 3E | `plugins/mits_mc_3e` | NOT_STARTED | address ranges, binary frames, read/write |
| Mitsubishi MC 4E | `plugins/mits_mc_4e` | NOT_STARTED | serial number handling, read/write |
| Mitsubishi MC A1E | `plugins/mits_mc_a1e` | NOT_STARTED | address parsing, read/write |
| Siemens S7 | `plugins/s7comm` | NOT_STARTED | areas/types, batching, reconnect |
| OPC UA | `plugins/opcua` | NOT_STARTED | endpoint/security, browse/read/write, subscriptions |
| Omron FINS TCP | `plugins/omron_fins` | NOT_STARTED | addressing, node negotiation, read/write |
| Omron FINS UDP | `plugins/omron_fins_udp` | NOT_STARTED | addressing, read/write, timeout |
| FANUC FOCAS | `plugins/focas` | NOT_STARTED | native-library loading and CNC values |
| JD50 NCMON | `plugins/jd50_ncmon` | NOT_STARTED | connection and CNC value parity |

## Data platform and dashboards

| Capability | Legacy reference | Status | Acceptance evidence |
|---|---|---|---|
| PostgreSQL data source | `dashboard-service` | IN_PROGRESS | Persisted PostgreSQL configuration, AES-GCM password ciphertext, connection/schema/query APIs; dataset binding and target-database acceptance remain |
| MongoDB data source | `dashboard-service` | NOT_STARTED | — |
| CSV and uploaded-file data source | `dashboard-service` | NOT_STARTED | — |
| HTTP data source | `dashboard-service` | NOT_STARTED | — |
| Connection validation and schema discovery | `dashboard-service`, `data-board` | IN_PROGRESS | Temporary and persisted PostgreSQL connection tests plus table/view and column metadata APIs; other source types remain |
| Query validation, planning and pushdown | `dashboard-service/query-engine` | IN_PROGRESS | Single-statement SELECT validation, comment/write rejection and bounded LIMIT enforcement; logical-plan pushdown remains |
| Filter, project, aggregate, having, order and limit | `dashboard-service/query-engine` | NOT_STARTED | — |
| Dataset CRUD, folders, copy and move | `dashboard-service`, `data-board` | IN_PROGRESS | PostgreSQL-backed CRUD, folders, protected deletion, copy and move are implemented; acceptance and bulk operations remain | `modules/query-api/src/test/kotlin/ai/moying/iview/query/DatasetServiceTest.kt` |
| Dataset schema, query, preview, explain and export | `dashboard-service`, `data-board` | IN_PROGRESS | Dataset SQL validation, preview, field metadata, PostgreSQL JSON Explain and bounded CSV export are implemented; schema cache remains | `modules/query-api/src/test/kotlin/ai/moying/iview/query/CsvExportTest.kt` |
| Data models, drift detection, sync and publish | `dashboard-service`, `data-board` | IN_PROGRESS | Dataset-backed model CRUD, versioned schema snapshots, manual sync, drift state and explicit publish are implemented; scheduled sync and downstream binding remain | `modules/query-api/src/test/kotlin/ai/moying/iview/query/DataModelServiceTest.kt` |
| Dashboard CRUD and folders | `dashboard-service`, `data-board` | IN_PROGRESS | Dashboard draft CRUD, optional model binding and protected nested folders are implemented; canvas editing remains | `modules/query-api/src/test/kotlin/ai/moying/iview/query/DashboardServiceTest.kt` |
| Snapshots and publish | `dashboard-service`, `data-board` | IN_PROGRESS | Immutable versioned snapshots and explicit publish are implemented; public sharing and runtime routing remain | `modules/query-api/src/test/kotlin/ai/moying/iview/query/DashboardServiceTest.kt` |
| Dashboard templates and import/export | `dashboard-service`, `data-board` | IN_PROGRESS | Validated portable `iview-dashboard` v1 export/import APIs and frontend download/file-import workflow are available; reusable template library and legacy-format compatibility remain | `iView-frontend/app.js` |
| Operation records and export | `dashboard-service`, `data-board` | NOT_STARTED | — |
| Low-code canvas, grid/free layout and layers | `data-board` | IN_PROGRESS | Versioned grid/free canvas document and bounded component layout validation are implemented; the frontend can read/edit/save documents and add supported components, while drag-and-drop layers UI remains | `modules/query-api/src/test/kotlin/ai/moying/iview/query/DashboardDocumentValidatorTest.kt` |
| Charts, VChart, tables, information and decoration components | `data-board` | IN_PROGRESS | Preview runtime renders bound metric values, bounded data tables and numeric bar charts; VChart and the complete component library remain | `iView-frontend/app.js` |
| Component data binding and dynamic variables | `data-board` | IN_PROGRESS | Components bind only to published models; the preview runtime reads bounded model data for metric/table/chart summaries; variable substitution in query parameters remains | `iView-frontend/app.js` |
| Conditional styles | `data-board` | IN_PROGRESS | Document-level conditional style rules are validated and applied in preview from explicit `props.preview_data`; live data binding and production renderer coverage remain | `iView-frontend/app.js` |
| Component interactions and parameterized jumps | `data-board` | IN_PROGRESS | Validated click/change definitions; preview runtime now executes click filter, variable-setting and target-dashboard navigation interactions, while full component event coverage remains | `iView-frontend/app.js` |
| Batch container, tuple component and pagination | `data-board` | IN_PROGRESS | Preview supports table pagination, tuple fields, and repeat containers whose child instances receive row-scoped click interactions; visual repeat composition controls are available, while drag/drop nesting remains | `iView-frontend/app.js` |
| Preview, published runtime and responsive scaling | `data-board` | IN_PROGRESS | Draft and immutable published-snapshot previews with proportional canvas scaling and bounded component placeholders are available; data binding and responsive runtime remain | `iView-frontend/app.js` |
| Theme, animation, i18n and icon support | `data-board` | NOT_STARTED | — |

## Migration completion condition

The Go MySCADA runtime can be retired only when every required row is `VERIFIED`, all configured
production devices pass protocol acceptance, historical/configuration data migration is rehearsed,
and rollback procedures have been exercised.
