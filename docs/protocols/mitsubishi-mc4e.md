# 三菱 MC 4E

`mits_mc_4e` 是 iView 的原生 JVM 三菱 MC Protocol 4E 二进制帧 TCP 驱动，适用于支持 MC 4E 帧的 Q、L、iQ-R 系列通信模块。它不调用旧 Go 插件。

## 设备连接配置

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `host` | 无 | PLC 或以太网通信模块地址，必填 |
| `port` | `5000` | MC Protocol TCP 端口 |
| `timeout` | `5000` | 连接与读写超时，毫秒 |
| `network_number` | `0` | 网络号，也接受 `networkNumber` |
| `pc_number` | `255` | 站号，也接受 `pcNumber`、`plc_number` |
| `module_io` | `0x03ff` | 请求目标模块 I/O 号，也接受 `moduleIo` |
| `module_station` | `0` | 多站号，也接受 `moduleStation` |
| `monitoring_timer` | `10` | MC 监视定时器，也接受 `monitoringTimer` |

支持十进制、`0x` 前缀十六进制配置值。每个请求都带递增的 16 位序列号，并校验响应序列号和路由，避免并发或异常网络下串帧后误读数据。

## 点位

支持 `X`、`Y`、`M`、`SM`、`L`、`F`、`V`、`B`、`SB`、`D`、`SD`、`W`、`SW`、`R`、`TC`、`TS`、`TN`、`CC`、`CS`、`CN`、`S`。其中 `X`、`Y`、`B`、`SB`、`W`、`SW` 地址按十六进制解释，其余按十进制解释；例如 `X1A` 是地址 `0x1A`，`D100` 是十进制 100。

布尔位软元件支持读写（`X` 输入继电器只读）；字软元件支持 `INT16`、`UINT16`、`INT32`、`UINT32`、`INT64`、`UINT64`、`FLOAT32`、`FLOAT64`。`STRING`、`BYTES` 暂不支持。点位可使用 `register_type`、`address_scale`、`address_offset`、`value_scale`、`value_offset` 和 `word_order`；`ABCD` 为低字在前，`CDAB` 为高字在前，旧值 `BADC` 按 `CDAB` 兼容。

## 稳定性边界

TCP I/O 失败时驱动关闭连接、重连并重试一次；超时、离线、配置错误和 PLC 结束码分别映射为相应点位质量。帧级自动化测试覆盖路由、序列号、地址编码和基本读寄存器流程，但在投产前仍必须以现场 PLC、网络拓扑及连续采集负载完成验收。
