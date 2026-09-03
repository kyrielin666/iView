# iView

iView is a JVM-based industrial data and visualization platform. It combines the complete device
communication and management capabilities of MySCADA with the dataset, query, low-code dashboard,
and industrial analytics capabilities of the existing dashboard system.

The repositories `dashboard-service`, `data-board`, `my-scada-next`, `scada-preview`, and
`dashboard-plugins` are migration references only and are not modified by this project.

## Target capabilities

- Industrial protocols: Modbus TCP/RTU, Mitsubishi MC 3E/4E/A1E, S7, OPC UA, Omron FINS TCP/UDP,
  FANUC FOCAS, and JD50 NCMON.
- Device templates, collection points, control points, diagnosis, scheduling, realtime values,
  history, alarms, and outbound MQTT/HTTP delivery.
- Users, roles, departments, menus, dictionaries, SSO, and platform integrations.
- Data sources, datasets, SQL filtering, query planning, data models, dashboards, templates,
  snapshots, publishing, import/export, and operation records.
- Machine-state intervals, shifts, availability/utilization, production, quality, and OEE.
- Low-code dashboard editing, component interaction, conditional styling, and realtime display.

## Modules created in the foundation milestone

```text
apps/server             Spring Boot application and platform HTTP entry
modules/core-model      Shared device and point-value vocabulary
modules/protocol-spi    Stable JVM protocol-driver contract
modules/protocol-modbus-tcp  Native JVM Modbus TCP driver and frame codec
modules/device          Framework-independent device catalog domain and validation
modules/device-jdbc     JDBC/Flyway persistence adapter for the device catalog
modules/collector       Driver registry and collection orchestration boundary
modules/oee             Deterministic state-interval and OEE calculations
modules/query-api       Dataset/query contracts shared with query implementations
```

Additional modules are added only with an implemented vertical slice; the target module map is in
`docs/architecture.md`.

## Build

Requirements: JDK 21 and Gradle 8.14+.

```shell
gradle test
gradle :apps:server:bootRun
```

The server defaults to port `8080`. Health is available at `/actuator/health`, and the foundation
capability manifest is available at `/api/v1/platform/capabilities`.

The first migrated business slice is the device catalog. It provides persistent device groups,
device templates, collection points, and device CRUD under `/api/v1` with legacy-compatible JSON
field names. Local development uses a persistent H2 file under `apps/server/data`; tests use
in-memory H2, and the `production` profile uses PostgreSQL.

The first native protocol slice is Modbus TCP. It implements configuration validation, TCP
diagnosis, register/coil reads, holding-register/coil writes, legacy address formats, scaling,
byte order, timeout handling, and one reconnect attempt without starting a Go process.
