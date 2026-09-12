# iView

![iView 轻量化工业看板与 SCADA 平台](docs/images/iview-hero.png)

**iView 是一个基于 Kotlin / Java 的轻量化工业数据看板与 SCADA 融合平台。**

项目目标是用统一的 JVM 后端替代原有 MySCADA 和数字看板体系，将设备通信、数据采集、SQL 数据集、生产分析与低代码看板组合成一套容易部署、容易二次开发的工程底座。

> 当前处于持续迁移阶段。原项目 `dashboard-plugins`、`dashboard-service`、`data-board`、`my-scada-next`、`scada-preview` 仅作为功能参考，不会被 iView 修改或作为运行时依赖。

## 项目特点

- 前后端独立部署：JVM 后端与纯 Web 前端完全分离。
- 原生 JVM 协议驱动：已实现 Modbus TCP/RTU、三菱 MC 3E、Siemens S7 和 OPC UA 的读写、诊断与重连。
- 工业数据闭环：设备采集、历史数据、告警、控制、生产记录、班次与 OEE。
- 数据看板链路：PostgreSQL 数据源 → 安全 SQL 数据集 → 已发布数据模型 → 看板组件。
- 低代码文档：支持画布、组件、变量、条件样式、交互、发布快照和模板导入导出。
- 轻量化：前端无需复杂构建框架即可运行，后端按领域模块拆分，适合私有化交付和工程定制。
- 安全查询：只允许只读 SQL，限制返回数量；动态变量通过 JDBC 参数绑定，避免字符串拼接。

## 系统架构

```text
iView-frontend
  看板编辑 / 设备管理 / 数据资产 / 告警与推送
                         │ HTTP API
                         ▼
iView JVM Backend
  ├─ 设备目录、采集、控制、告警与推送
  ├─ 协议 SPI 与五种原生 JVM 工业协议驱动
  ├─ 数据源、数据集、数据模型与安全查询
  ├─ 看板文档、快照、模板与运行时数据绑定
  └─ 机台状态、生产计划、产量与 OEE
                         │
              PostgreSQL / H2 / 工业设备
```

看板只能消费数据集、已发布数据模型或显式实时订阅，不会直接调用协议驱动。原始采样、机台状态区间和计算结果分别保存，OEE 等计算结果保留规则版本与时间窗口。

## 当前已迁移能力

### SCADA 与设备

- 设备组、设备模板、采集点和控制点管理
- 设备连接测试、诊断、会话复用、超时与重连
- Modbus TCP/RTU、三菱 MC 3E、Siemens S7 和 OPC UA 采集与控制
- 实时值、历史采样、设备统计和采集调度
- HTTP / MQTT 推送、重试、队列状态与投递日志
- 设备异常/恢复告警、分类规则和记录清理

### 生产与分析

- 机台状态规则、状态标准化和版本化状态区间
- 产量计数规则、断点续算及幂等生产记录
- 班次、跨日班次、计划停机时间
- 可用率、稼动率与 OEE 时间窗口计算

### 数据平台与看板

- PostgreSQL 数据源、连接测试与结构发现
- 数据集 CRUD、目录、复制、移动、预览、Explain 与 CSV 导出
- 数据模型字段同步、漂移检测、发布和安全变量查询
- 看板草稿、文件夹、发布快照、预览与模板导入导出
- 指标、表格、条形图、文本、装饰、容器和元组组件
- 条件样式、组件交互、参数跳转和重复容器编排
- 草稿/发布预览、真实模型数据绑定和表格分页

## 快速启动

### 后端

环境要求：JDK 21。

```bash
cd iView
./gradlew test
./gradlew :apps:server:bootRun
```

Windows：

```powershell
cd iView
.\gradlew.bat test
.\gradlew.bat :apps:server:bootRun
```

默认地址：

- 后端 API：`http://localhost:8080`
- 健康检查：`http://localhost:8080/actuator/health`

开发环境默认使用本地 H2 文件；生产环境通过环境变量配置 PostgreSQL。数据源密码使用 AES-GCM 加密保存，生产环境必须设置固定的 `IVIEW_DATASOURCE_SECRET`。

### 前端

前端位于独立项目 `iView-frontend`：

```bash
cd iView-frontend
npm run dev
```

访问 `http://localhost:3000`。本地开发服务器会把 `/api/**` 和 `/actuator/**` 代理到 `http://localhost:8080`。

## 项目结构

```text
apps/server                    Spring Boot HTTP 入口与装配
modules/protocol-spi           协议驱动统一接口
modules/protocol-modbus-tcp    原生 JVM Modbus TCP 驱动
modules/protocol-modbus-rtu    原生 JVM Modbus RTU 串口驱动
modules/protocol-mitsubishi-mc3e 原生 JVM 三菱 MC 3E TCP 驱动
modules/protocol-s7            基于 PLC4X 的 Siemens S7 TCP 驱动
modules/protocol-opcua         基于 Eclipse Milo 的 OPC UA 客户端驱动
modules/device                 设备目录领域逻辑
modules/collector              采集编排与会话管理
modules/telemetry              原始点位采样
modules/alarm                  设备告警
modules/outbound               HTTP / MQTT 数据推送
modules/machine-state          机台状态区间
modules/production             生产记录
modules/production-plan        班次与计划停机
modules/oee                    稼动率与 OEE 计算
modules/query-api              数据源、数据集、模型与看板领域
modules/*-jdbc                 JDBC 持久化适配器
docs                           架构与 API 文档
```

## 适用场景

- 设备联网、状态监控与生产透明化
- 车间电子看板、Andon、OEE 与稼动率项目
- 轻量 MES/SCADA 项目的数据采集与展示底座
- 企业内部 SQL 数据看板与工业数据门户
- 需要私有化部署、二次开发或协议扩展的工程项目

## 后续方向

- 继续扩展三菱 MC 4E/A1E、FINS、FOCAS，并完善 OPC UA 证书安全端点
- 完成身份认证、RBAC、部门、菜单与审计日志
- 增加 MongoDB、CSV/文件和 HTTP 数据源
- 完善拖拽式图层编辑、完整图表组件库与响应式发布运行时
- 完成真实设备验收、压力测试、备份恢复与升级回滚

## 文档入口

- [架构说明](docs/architecture.md)
- [前后端分离部署](docs/architecture/frontend-backend-separation.md)
- [看板 API](docs/api/dashboards.md)
- [数据集 API](docs/api/datasets.md)
- [设备目录 API](docs/api/device-catalog.md)
- [身份认证 API](docs/api/authentication.md)
- [备份恢复 API](docs/api/backups.md)
- [Modbus TCP](docs/protocols/modbus-tcp.md)
- [Modbus RTU](docs/protocols/modbus-rtu.md)
- [三菱 MC 3E](docs/protocols/mitsubishi-mc3e.md)
- [Siemens S7](docs/protocols/s7.md)
- [OPC UA](docs/protocols/opcua.md)

如果你在寻找一套可裁剪、可私有部署、能继续扩展工业协议和行业组件的轻量化看板底座，iView 正朝这个方向持续完善。
