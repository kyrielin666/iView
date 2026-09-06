# Modbus RTU

`modbus_rtu` 是 iView 的原生 JVM 串口驱动，使用 jSerialComm，不依赖旧 Go 插件。

设备连接参数：`serial_port`、`baud_rate`（1200–115200 的标准档位）、`data_bits`、`stop_bits`、
`parity`（`N`、`E`、`O`）、`slave_id`（1–247）和 `timeout`。

支持线圈和保持寄存器读写、RTU CRC 校验、INT/UINT/FLOAT 类型和四种字节序。串口 I/O 超时或中断时，
会关闭并重开串口后重试一次；现场验收应覆盖 USB/RS485 驱动权限、总线终端电阻和设备异常响应。
