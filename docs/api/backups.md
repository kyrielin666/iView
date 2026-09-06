# 备份与恢复 API

所有备份接口都受管理员权限保护。H2 内置部署支持以下逻辑备份流程：

- `POST /api/v1/backups`：创建完整 SQL 逻辑备份，计算 SHA-256，并按 `IVIEW_BACKUP_RETENTION_COUNT` 清理旧文件。
- `GET /api/v1/backups`：列出服务受控目录内的备份及校验摘要。
- `POST /api/v1/backups/{name}/validate`：重新计算摘要并检查文件有效性。
- `POST /api/v1/backups/restore`：仅在维护窗口设置 `IVIEW_BACKUP_RESTORE_ENABLED=true` 并重启后可调用；请求必须包含精确确认文本 `RESTORE <name>`。

恢复会重建整个 H2 数据库，必须先停止采集和外部写入。生产 PostgreSQL 不会调用不受控的
本地命令；后续将接入由运维账户管理的 `pg_dump` / `pg_restore` 作业和恢复演练记录。
