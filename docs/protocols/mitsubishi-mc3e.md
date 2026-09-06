# 三菱 MC 3E

`mits_mc_3e` 是 iView 的原生 JVM 三菱 MC Protocol 3E 二进制帧 TCP 驱动，不依赖旧 Go 插件。

## 设备连接参数

| 参数 | 默认值 | 说明 |
|---|---:|---|
| `host` | — | PLC IP 或主机名，必填；兼容 `ip_address` |
| `port` | `5000` | PLC MC Protocol TCP 端口 |
| `timeout` | `5000` | 建连和单次读写超时，单位毫秒 |
| `network_number` | `0` | 网络号，0–255 |
| `pc_number` | `255` | PLC 号，0–255 |
| `module_io` | `0x03ff` | 请求目标模块 I/O 号 |
| `module_station` | `0` | 请求目标模块站号，0–255 |
| `monitoring_timer` | `10` | PLC 监视定时器，单位为 250 ms |

参数同时兼容对应的 camelCase 名称。网络号、PLC 号、模块 I/O 号和站号会写入请求帧，并与响应帧核对。

## 点位配置

地址写成 `D100`、`X10`、`W1A` 等完整软元件地址；也可以只填数字，并通过 `register_type`
（兼容 `registerType`）补充前缀。

- X、Y 地址按八进制解释，与 GX Works 的显示一致。
- B、SB、W、SW 地址按十六进制解释。
- M、D、L、R、S、定时器和计数器等地址按十进制解释。
- 支持 X、Y、M、SM、L、F、V、B、SB、D、SD、W、SW、R、TC、TS、TN、CC、CS、CN、S。
- `word_order` 支持 `ABCD`（低字在前，默认）和 `CDAB`（高字在前）；兼容旧字段 `wordOrder`，并兼容旧值 `BADC`。
- `address_scale`、`address_offset`、`value_scale`、`value_offset` 可用于地址换算和工程量缩放。
- 支持 BOOLEAN、INT/UINT 16/32/64、FLOAT32 和 FLOAT64。STRING、BYTES 暂不支持。

X 输入继电器始终禁止写入。其他点位还会遵守 iView 点位本身的读写权限。BOOLEAN 位软元件使用位子命令和半字节打包；
字软元件上的 BOOLEAN 使用单字读写。

## 通信与故障行为

驱动按请求串行读写，依据响应数据长度精确收满一帧，校验 3E 响应副头部、路由、长度和 PLC 结束码。
TCP I/O 失败时关闭连接、重新建连并重试一次。超时、离线、配置错误和协议异常分别映射为
`TIMEOUT`、`OFFLINE`、`BAD_CONFIGURATION` 和 `BAD_RESPONSE` 点位质量。

确定性测试覆盖地址进制、规范读写帧、位数据打包、真实本机 TCP 交换、PLC 异常响应、字序转换和断线重连。
正式项目仍需在目标 FX5U/Q/L/iQ-R PLC 上确认端口、路由、软元件范围、连续采集压力和梯形图覆盖输出的现场行为。
