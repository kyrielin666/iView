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
| Control points, execution and control logs | `scada-preview`, `my-scada-next` | NOT_STARTED | — |
| Connection test and device diagnosis | `scada-preview` | IN_PROGRESS | Modbus TCP test/diagnose API and Spring integration; API integration test |
| Collector scheduling and manual collection | `scada-preview` | NOT_STARTED | Driver registry exists, but scheduling and collection do not |
| Connection reuse, timeout and reconnect | `scada-preview` | IN_PROGRESS | Modbus TCP session timeout and single reconnect attempt; cross-device pool not implemented |
| Realtime values and device statistics | `scada-preview` | IN_PROGRESS | Point-quality vocabulary only |
| Device process/plugin monitoring | `scada-preview` | NOT_STARTED | Replaced by JVM driver/session monitoring |
| MQTT and HTTP push configuration | `scada-preview` | NOT_STARTED | — |
| Push logs, retry and enable/disable | `scada-preview` | NOT_STARTED | — |
| Alarm rules, records and cleanup | `dashboard-service`, `scada-preview` | NOT_STARTED | — |
| Machine-state rules and standardization | `dashboard-service` | NOT_STARTED | — |
| Runtime periods and daily accumulation | `dashboard-service` | IN_PROGRESS | OEE interval calculator foundation |
| Production records and quantity rules | `dashboard-service` | NOT_STARTED | — |
| Shifts, calendars and planned downtime | product requirement | NOT_STARTED | — |
| Availability/utilization and OEE | product requirement | IN_PROGRESS | Deterministic calculator and tests created |
| Tiangong config, sync and last-will | `scada-preview`, `my-scada-next` | NOT_STARTED | — |
| Resource/system monitoring | `scada-preview`, `my-scada-next` | NOT_STARTED | — |
| Backup and restore | `scada-preview` | NOT_STARTED | — |
| OpenAPI device/template import | `scada-preview` | NOT_STARTED | — |

## Industrial protocols

| Protocol | Legacy reference | Status | Required acceptance |
|---|---|---|---|
| Modbus TCP | `plugins/modbus_tcp` | IN_PROGRESS | JVM frame read/write, endian, scaling, timeout and reconnect tests; batching and hardware acceptance remain |
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
| PostgreSQL data source | `dashboard-service` | NOT_STARTED | — |
| MongoDB data source | `dashboard-service` | NOT_STARTED | — |
| CSV and uploaded-file data source | `dashboard-service` | NOT_STARTED | — |
| HTTP data source | `dashboard-service` | NOT_STARTED | — |
| Connection validation and schema discovery | `dashboard-service`, `data-board` | NOT_STARTED | — |
| Query validation, planning and pushdown | `dashboard-service/query-engine` | NOT_STARTED | Query DTOs exist, but validation/planning/pushdown do not |
| Filter, project, aggregate, having, order and limit | `dashboard-service/query-engine` | NOT_STARTED | — |
| Dataset CRUD, folders, copy and move | `dashboard-service`, `data-board` | NOT_STARTED | — |
| Dataset schema, query, preview, explain and export | `dashboard-service`, `data-board` | NOT_STARTED | Query DTOs exist, but no executable dataset service |
| Data models, drift detection, sync and publish | `dashboard-service`, `data-board` | NOT_STARTED | — |
| Dashboard CRUD and folders | `dashboard-service`, `data-board` | NOT_STARTED | — |
| Snapshots and publish | `dashboard-service`, `data-board` | NOT_STARTED | — |
| Dashboard templates and import/export | `dashboard-service`, `data-board` | NOT_STARTED | — |
| Operation records and export | `dashboard-service`, `data-board` | NOT_STARTED | — |
| Low-code canvas, grid/free layout and layers | `data-board` | NOT_STARTED | — |
| Charts, VChart, tables, information and decoration components | `data-board` | NOT_STARTED | — |
| Component data binding and dynamic variables | `data-board` | NOT_STARTED | — |
| Conditional styles | `data-board` | NOT_STARTED | — |
| Component interactions and parameterized jumps | `data-board` | NOT_STARTED | — |
| Batch container, tuple component and pagination | `data-board` | NOT_STARTED | — |
| Preview, published runtime and responsive scaling | `data-board` | NOT_STARTED | — |
| Theme, animation, i18n and icon support | `data-board` | NOT_STARTED | — |

## Migration completion condition

The Go MySCADA runtime can be retired only when every required row is `VERIFIED`, all configured
production devices pass protocol acceptance, historical/configuration data migration is rehearsed,
and rollback procedures have been exercised.
