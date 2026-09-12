# OPC UA

`opcua` 是 iView 基于 Eclipse Milo 的 JVM OPC UA 客户端驱动，支持真实会话、NodeId 读写、状态质量和连接恢复。

## 设备连接参数

| 参数 | 默认值 | 说明 |
|---|---:|---|
| `endpoint_url` | — | `opc.tcp://host:port/path` 端点，必填；兼容 `endpointUrl` |
| `timeout` | `5000` | 请求和连接诊断超时，单位毫秒 |
| `username` | 空 | 可选用户名；留空时使用匿名身份 |
| `password` | 空 | 可选密码，配置密码时必须填写用户名 |
| `security_policy` | `None` | 当前可运行配置为 `None` |
| `security_mode` | `None` | 当前可运行配置为 `None` |

当前版本有意拒绝尚未配置受信证书的 Sign/SignAndEncrypt 连接，不会把安全端点静默降级为明文。证书库、受信服务器证书和吊销校验接入后再开放相应安全模式。

## 点位与数据类型

点位地址使用标准可解析 NodeId，例如：

- 数字节点：`ns=2;i=10853`
- 字符串节点：`ns=3;s=Machine/Speed`

也可以在点位配置中使用 `node_id` 或 `nodeId` 覆盖地址。读写支持 BOOLEAN、INT/UINT 16/32/64、FLOAT32、FLOAT64、STRING 和 BYTES；无符号数和 ByteString 会转换为可持久化的数据。

服务端 Good、Uncertain 和 Bad 状态分别映射为 `GOOD`、`UNCERTAIN` 与 `BAD_RESPONSE`。配置错误、超时和会话/链路异常分别映射为 `BAD_CONFIGURATION`、`TIMEOUT` 与 `OFFLINE`。Milo 自带会话恢复，驱动在一次操作失败时还会重建客户端并重试一次；服务端明确返回的坏状态不会被当成成功。

正式项目应在目标服务器上验证命名空间稳定性、用户权限、写入类型、采样周期、会话上限和断网恢复。生产环境启用加密前必须完成应用证书与服务器证书信任配置。
