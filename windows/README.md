# iView Windows 便携运行器

这是按需启动的本机运行器，不会注册成一直随 Windows 启动的系统服务：双击启动，关闭时运行停止脚本即可。它会启动后端 Jar 和前端代理，并把日志写入 `iView/runtime/logs/`。

## 前置条件

- Windows 10/11
- JDK 21（设置 `JAVA_HOME`，或将 `java.exe` 加入 `PATH`）
- Node.js 20+（将 `node.exe` 加入 `PATH`）

首次使用或代码更新后，先停止已经运行的 iView，再运行 PowerShell：

```powershell
cd D:\code\iView\windows
.\Stop-iView.ps1
.\Build-iView.ps1
```

随后双击 `Start-iView.cmd`，或执行：

```powershell
.\Start-iView.ps1
```

默认地址为前端 `http://127.0.0.1:3000`、后端健康检查 `http://127.0.0.1:8080/actuator/health`。如端口冲突，可用 `./Start-iView.ps1 -FrontendPort 3001 -BackendPort 8081`。

查看日志：

```powershell
.\Logs-iView.ps1 -Service backend
.\Logs-iView.ps1 -Service frontend -ErrorLog
```

停止：双击 `Stop-iView.cmd`，或运行 `./Stop-iView.ps1`。它会校验 PID、进程类型和启动时间，不会按端口误杀其他程序。

## PostgreSQL 备份恢复

生产环境若使用 PostgreSQL，另需由管理员明确设置：`IVIEW_BACKUP_POSTGRES_ENABLED=true`，并在 `PATH` 提供 `pg_dump` 与 `pg_restore`（也可设置 `IVIEW_BACKUP_POSTGRES_PG_DUMP_COMMAND`、`IVIEW_BACKUP_POSTGRES_PG_RESTORE_COMMAND`）。恢复还必须设置 `IVIEW_BACKUP_RESTORE_ENABLED=true` 后重启。
