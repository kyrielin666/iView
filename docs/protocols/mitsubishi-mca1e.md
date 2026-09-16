# 三菱 MC A1E

`mits_mc_a1e` 是 iView 的原生 JVM MELSEC-A MC 1E 二进制帧 TCP 驱动，迁移自旧 SCADA 的 A1E 设备能力，但不再依赖 Go 插件进程。

设备连接字段为 `host`（或 `ip_address`）、`port`（默认 `5001`）、`timeout`（默认 `5000` 毫秒）和 `monitoring_timer`（默认 `10`，单位由 PLC 模块定义）。旧 A 系列 AJ71E71 / QJ71E71 的端口以现场工程设置为准。

1E 帧仅支持旧项目已有的 `D`、`W`、`R` 字软元件和 `M` 位软元件；`W` 地址按十六进制解释，其他地址按十进制解释。支持布尔、16/32/64 位整数和浮点读写，以及 `register_type`、地址/数值缩放和 `word_order`；旧值 `BADC` 按高字在前的 `CDAB` 兼容。不支持 `STRING`、`BYTES`。

TCP I/O 出错后会关闭连接、重新建立并重试一次；PLC 结束码、超时、离线和配置问题会映射为相应的点位质量。帧和会话已做本地自动化测试，现场投产前仍需用实际 A 系列 PLC 与通信模块进行连续采集验收。
