# Modbus TCP driver

`modules/protocol-modbus-tcp` is an in-process JVM implementation. It does not execute or depend on
the legacy Go plugin.

## Device properties

| Property | Default | Notes |
|---|---:|---|
| `host` / `ip_address` | required | IPv4, IPv6 or hostname |
| `port` | 502 | 1–65535 |
| `slave_id` / `slaveId` | 1 | 1–255 |
| `timeout` | 5000 | connect/read timeout in milliseconds |
| `maxBatchSize` / `max_batch_size` | 1 | merge same-register-type reads up to 125 addresses |

## Point properties

The driver accepts legacy snake_case and camelCase names. `register_type` is one of `coil`,
`discrete`, `input`, or `holding`. `byte_order` supports `big`, `little`, `cdab`, and `badc`.
`address_scale`, `address_offset`, `value_scale`, and `value_offset` retain the legacy point
calculation behavior. Raw addresses and reference addresses such as `30001` and `40001` are
accepted. When batching is enabled, values keep their original point order and per-point quality.

Reads support coils, discrete inputs, input registers, and holding registers. Writes support coils
and holding registers. A transport I/O failure closes the stale socket, reconnects once, and retries
the request. The migration ledger remains `IN_PROGRESS` until batching and physical-device
acceptance are complete.
