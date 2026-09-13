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

## Control points and logs

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/templates/{id}/control-points` | List template control points |
| GET/POST | `/api/v1/control-points/{id}` / `/api/v1/control-points` | Get / create a control point |
| PUT/DELETE | `/api/v1/control-points/{id}` | Update / delete a control point |
| POST | `/api/v1/control-points/batch` | Batch-create control points |
| DELETE | `/api/v1/control-points/batch/{ids}` | Delete comma-separated control point IDs |
| POST | `/api/v1/control-points/{id}/execute` | Write a control value to one device |
| GET | `/api/v1/control-logs` | Page control audit logs; filter by `device_id` or `control_point_id` |

Control execution requires an enabled device whose template owns the enabled control point. It
reuses the JVM protocol session and records both successful and failed protocol writes. The
`point_config.writeTargets` array enables one logical control to write several addresses; the
request value may be an object keyed by target key/name or an array in target order.

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
| POST | `/api/v1/devices/{id}/collect` | Collect every enabled template point |
| POST | `/api/v1/devices/{id}/collect/{pointId}` | Collect one point |
| GET | `/api/v1/devices/{id}/realtime` | Get each template point's latest sample |
| GET | `/api/v1/devices/{id}/history` | Read raw samples; optional `from`, `to`, `limit` |
| GET | `/api/v1/devices/statistics` | Device, enabled and recently-online counts |
| GET | `/api/v1/collector/sessions` | Active JVM protocol-session status |

连接测试、诊断、手动采集、实时值、原始历史和控制写入已覆盖 Modbus TCP/RTU、三菱 MC 3E、Siemens S7 与 OPC UA。驱动内部重连仍失败，或驱动将错误映射为 `TIMEOUT`/`OFFLINE` 点位质量时，采集引擎会关闭旧会话、短暂退避后重新建连并再采集一次；API 的 `attempts` 与 `recovered` 字段用于区分直接成功、重连恢复和最终失败。投产前仍需按现场 PLC、串口转换器和网络条件完成硬件验收及持续压力测试。
