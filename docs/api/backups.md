# 备份与恢复 API

所有备份接口都受管理员权限保护。H2 内置部署生成 SQL 逻辑备份；PostgreSQL 部署在显式开启 `IVIEW_BACKUP_POSTGRES_ENABLED=true` 且安装、配置 `pg_dump` / `pg_restore` 后生成 custom 格式备份。两个引擎共用以下流程：

- `POST /api/v1/backups`：创建备份及对应 `.sha256` 校验文件，并按 `IVIEW_BACKUP_RETENTION_COUNT` 清理旧文件。
- `GET /api/v1/backups`：列出服务受控目录内的备份及校验摘要。
- `POST /api/v1/backups/{name}/validate`：重新计算摘要，与创建时保存的摘要比较；PostgreSQL 另运行 `pg_restore --list` 检查归档格式。没有校验文件的旧备份会标为无效，需由运维另行核实。
- `POST /api/v1/backups/restore`：仅在维护窗口设置 `IVIEW_BACKUP_RESTORE_ENABLED=true` 并重启后可调用；请求必须包含精确确认文本 `RESTORE <name>`。

恢复必须在维护窗口停止采集和外部写入。H2 恢复会重建整个数据库；PostgreSQL 使用配置的 `pg_restore --clean --if-exists` 对当前库恢复，属于破坏性操作。命令由部署环境的运维账户管理，数据库密码仅经子进程环境变量传递。摘要文件用于发现意外损坏，不能替代离线、不可篡改备份。真实 PostgreSQL 实例上的恢复演练和业务数据核对仍待现场验收。
