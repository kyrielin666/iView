[CmdletBinding()]
param([switch]$Quiet)

$ErrorActionPreference = "Stop"
$ServerRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$PidRoot = Join-Path $ServerRoot "runtime\pids"
foreach ($name in @("frontend", "backend")) {
    $pidFile = Join-Path $PidRoot "$name.pid"
    if (-not (Test-Path $pidFile)) { continue }
    $record = try { Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json } catch { $null }
    $id = if ($null -ne $record -and $record.id) { "$($record.id)" } else { (Get-Content -LiteralPath $pidFile -Raw).Trim() }
    $process = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($process) {
        $expectedName = if ($name -eq "backend") { "java" } else { "node" }
        $sameStart = $null -ne $record -and $record.started_at -and ([datetime]$record.started_at).ToUniversalTime() -eq $process.StartTime.ToUniversalTime()
        if ($process.ProcessName -ne $expectedName -or -not $sameStart) {
            Write-Warning "PID $id 不再属于 iView $name，未终止该进程。"
        } else {
            Stop-Process -Id $process.Id -Force
            if (-not $Quiet) { Write-Host "已停止 $name（PID $id）。" }
        }
    }
    Remove-Item -LiteralPath $pidFile -Force
}
