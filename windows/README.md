# iView Windows 便携运行器

这里同时提供按需启动的便携运行器和正式 Windows 服务部署方式。便携方式适合开发调试；生产服务通过 WinSW 接入 Windows Service Control Manager，支持开机延迟启动、异常重启和按日期/大小滚动日志。

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

便携运行器在日志达到 20MB 时保留最近 10 份，可通过 `-LogMaxSizeMb` 和 `-LogKeepFiles` 调整。

## 正式 Windows 服务

从 [WinSW 官方发布页](https://github.com/winsw/winsw/releases)取得经过组织校验的 x64 可执行文件。先运行 `Build-iView.ps1`，再以管理员身份执行：

```powershell
.\Install-iViewService.ps1 -WinSwPath C:\tools\WinSW-x64.exe -Start
```

脚本注册 `iViewBackend` 和依赖它的 `iViewFrontend` 两个服务。服务日志位于 `runtime/logs`，每天或达到 20MB 时滚动，旧日志压缩并保留最近 30 份。升级与卸载：

```powershell
.\Update-iViewService.ps1
.\Uninstall-iViewService.ps1
```

升级脚本会先停止服务，将原 Jar 和前端 `dist` 备份到带时间戳的 `runtime/releases` 目录，构建成功后再启动；构建失败时保持服务停止并保留旧构建，避免半升级状态。生产安装前应将数据库、认证密钥和备份参数配置为系统级环境变量。

## PostgreSQL 备份恢复

生产环境若使用 PostgreSQL，另需由管理员明确设置：`IVIEW_BACKUP_POSTGRES_ENABLED=true`，并在 `PATH` 提供 `pg_dump` 与 `pg_restore`（也可设置 `IVIEW_BACKUP_POSTGRES_PG_DUMP_COMMAND`、`IVIEW_BACKUP_POSTGRES_PG_RESTORE_COMMAND`）。恢复还必须设置 `IVIEW_BACKUP_RESTORE_ENABLED=true` 后重启。
