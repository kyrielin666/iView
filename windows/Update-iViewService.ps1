[CmdletBinding()]
param()
$ErrorActionPreference = "Stop"
$ServerRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ServiceRoot = Join-Path $ServerRoot "runtime\services"
$ReleaseRoot = Join-Path $ServerRoot "runtime\releases\$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$backend = Join-Path $ServiceRoot "iViewBackend.exe"; $frontend = Join-Path $ServiceRoot "iViewFrontend.exe"
if (-not (Test-Path -LiteralPath $backend) -or -not (Test-Path -LiteralPath $frontend)) { throw "尚未安装 iView Windows 服务。" }
New-Item -ItemType Directory -Force -Path $ReleaseRoot | Out-Null
& $frontend stopwait 30sec; & $backend stopwait 30sec
$oldJar = Get-ChildItem (Join-Path $ServerRoot "apps\server\build\libs\*.jar") -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch "plain" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($oldJar) { Copy-Item -LiteralPath $oldJar.FullName -Destination $ReleaseRoot }
$frontendDist = Join-Path (Resolve-Path (Join-Path $ServerRoot "..")).Path "iView-frontend\dist"
if (Test-Path -LiteralPath $frontendDist) { Copy-Item -LiteralPath $frontendDist -Destination (Join-Path $ReleaseRoot "frontend-dist") -Recurse }
try {
    & (Join-Path $PSScriptRoot "Build-iView.ps1")
    & $backend start; & $frontend start
} catch {
    Write-Error "升级失败，服务保持停止。旧构建已备份到 $ReleaseRoot，请检查日志后人工回滚。"
    throw
}
Write-Host "iView 服务升级完成；升级前构建已备份到 $ReleaseRoot"
