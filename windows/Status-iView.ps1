$ErrorActionPreference = "Stop"
$ServerRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$PidRoot = Join-Path $ServerRoot "runtime\pids"
foreach ($name in @("backend", "frontend")) {
    $pidFile = Join-Path $PidRoot "$name.pid"; $record = if (Test-Path $pidFile) { try { Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json } catch { $null } } else { $null }; $id = if ($null -ne $record -and $record.id) { "$($record.id)" } else { "" }
    $process = if ($id) { Get-Process -Id $id -ErrorAction SilentlyContinue } else { $null }
    [pscustomobject]@{ Service = $name; Pid = $id; Running = [bool]$process; Started = if ($process) { $process.StartTime } else { $null } }
}
try { Invoke-RestMethod "http://127.0.0.1:8080/actuator/health" -TimeoutSec 3 | Select-Object status } catch { Write-Warning "后端健康检查未通过：$($_.Exception.Message)" }
