# 备份与恢复 API

所有备份接口都受管理员权限保护。H2 内置部署生成 SQL 逻辑备份；PostgreSQL 部署在显式开启 `IVIEW_BACKUP_POSTGRES_ENABLED=true` 且安装、配置 `pg_dump` / `pg_restore` 后生成 custom 格式备份。两个引擎共用以下流程：

- `POST /api/v1/backups`：创建备份及对应 `.sha256` 校验文件，并按 `IVIEW_BACKUP_RETENTION_COUNT` 清理旧文件。
- `GET /api/v1/backups`：列出服务受控目录内的备份及校验摘要。
- `POST /api/v1/backups/{name}/validate`：重新计算摘要，与创建时保存的摘要比较；PostgreSQL 另运行 `pg_restore --list` 检查归档格式。没有校验文件的旧备份会标为无效，需由运维另行核实。
- `POST /api/v1/backups/restore`：仅在维护窗口设置 `IVIEW_BACKUP_RESTORE_ENABLED=true` 并重启后可调用；请求必须包含精确确认文本 `RESTORE <name>`。

恢复必须在维护窗口停止采集和外部写入。H2 恢复会重建整个数据库；PostgreSQL 使用配置的 `pg_restore --clean --if-exists --exit-on-error --single-transaction` 对当前库恢复，属于破坏性操作；错误会使本次 PostgreSQL 事务回滚。数据库类型、启用开关和确认文本在调用恢复工具前检查。命令由部署环境的运维账户管理，数据库密码仅经子进程环境变量传递。摘要文件用于发现意外损坏，不能替代离线、不可篡改备份。

目前内置 PostgreSQL 命令仅支持不带查询参数与内嵌凭据的 JDBC URL；带 TLS/路由等参数的 URL 会明确拒绝，避免静默忽略连接配置。此类部署应使用配置一致的独立运维作业。H2 恢复不具备上述 PostgreSQL 事务回滚保证。

### 隔离恢复演练

`windows/Test-PostgresRestore.ps1 -PostgresBin C:\tools\pgsql\bin` 使用给定 PostgreSQL 工具在独立目录启动仅监听回环地址、SCRAM 认证的临时实例，运行真实集成测试后停止。可用 `-Port` 更改默认 `55439`。它不读取生产连接配置，不恢复生产库。运行记录保存到项目上级的 `.iview-runtime/postgres-rehearsals/`。

测试包括：全部应用数据库迁移、备份摘要与归档验证、外键/索引/序列/Unicode/JSON/时间值恢复，以及故意制造 DROP 依赖错误后的事务回滚；同时验证 PostgreSQL 字段目录与 SQL 血缘解析。普通 `gradlew test` 默认跳过该环境测试；跳过不代表验收通过。部署数据库的数据规模、扩展、权限、RPO/RTO 和维护窗口仍须另外验收。

Flyway 的通用迁移位于 `db/migration`；包含大文本列的 V10/V12/V13 按数据库分别位于 `db/vendor/h2` 和 `db/vendor/postgresql`。H2 版本保留原 SQL 内容及校验值，PostgreSQL 版本使用 `TEXT`。应用通过数据库厂商选择对应目录；已有库升级前仍需在维护窗口备份并验证迁移。
