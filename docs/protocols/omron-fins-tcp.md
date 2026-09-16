# OMRON FINS/TCP

`omron_fins` 是 iView 的原生 JVM FINS/TCP 驱动，面向使用 FINS 以太网通信的 OMRON CS/CJ/CP 系列 PLC，不依赖旧 Go 插件。

设备配置支持 `host`（或 `ip_address`）、`port`（默认 `9600`）、`timeout`（默认 `5000` 毫秒）、`node_number`、`local_node` 和 `network_number`。`node_number=0` 时自动使用 IPv4 地址最后一段；建立 TCP 后会执行标准节点地址握手，并以 PLC 返回的客户端/服务器节点号构造后续命令。

点位支持 `DM`、`CIO`、`WR`、`HR`、`AR`、当前 Bank 的 `EM`，以及只读的 `TIM_PV`、`CNT_PV`。带小数点的地址按位访问，例如 `CIO10.03`、`D50.7`；不带小数点的地址按字访问，例如 `D100`。多字数据支持 `big`、`little`、`cdab`、`badc` 字节序。

驱动实现 FINS/TCP 握手、内存区读写、SID/命令/结束码校验和 I/O 失败后的单次重连重试。自动化测试使用本地伪 PLC 验证真实 TCP 握手及帧交互；现场投产仍须用目标 PLC 做连续采集、批量点位吞吐和断网恢复验收。
