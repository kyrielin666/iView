[CmdletBinding()]
param()
$ErrorActionPreference = "Stop"
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw "卸载 Windows 服务必须以管理员身份运行 PowerShell。" }
$ServiceRoot = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")).Path "runtime\services"
foreach ($id in @("iViewFrontend", "iViewBackend")) {
    $exe = Join-Path $ServiceRoot "$id.exe"
    if (Test-Path -LiteralPath $exe) { & $exe stopwait 15sec; & $exe uninstall }
}
Write-Host "iView Windows 服务已卸载；配置和日志仍保留在 runtime 目录。"
