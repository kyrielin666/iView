# OMRON FINS/UDP

iView 内置 `omron_fins_udp` 驱动，适用于 CS/CJ/CP 系列启用 FINS/UDP 的控制器。此驱动直接发送 FINS 帧，不执行 FINS/TCP 的节点号协商。

## 设备连接

```json
{
  "host": "192.168.1.10",
  "port": 9600,
  "node_number": 10,
  "local_node": 1,
  "network_number": 0,
  "timeout": 5000,
  "retry_count": 1
}
```

- `node_number` 为 0 时，以 IP 最后一段推导 PLC 节点号。
- UDP 本身没有连接确认；诊断只检查本机套接字配置，首次真实点位采集才可确认 PLC 路由、节点号和防火墙。
- 每次请求校验 SID、命令码与 PLC End Code；超时或网络 I/O 会重建套接字并按 `retry_count` 重试。

## 点位

内存区和地址与 FINS/TCP 一致：`DM`、`CIO`、`WR`、`HR`、`AR`、`EM`，以及只读的 `TIM_PV`、`CNT_PV`。示例：`D100`、`CIO10.03`、`HR50`。

支持布尔、16/32/64 位整数与浮点数，支持 `big`、`little`、`cdab`、`badc` 字节序及 `value_scale`/`value_offset`。

## 批量采集说明

FINS/TCP 与 FINS/UDP 都会对同一内存区中相邻的字点位自动合并读取（最大 500 个字，允许不超过 16 个字的空洞），并在本地拆分为每个点位的类型、字节序和缩放结果。位点与不兼容区域仍独立读取，保证异常质量码不会被错误归并。现场投用时应根据 PLC 型号和网络质量调整模板的批量策略，并以连续验收报告确认单包上限。
