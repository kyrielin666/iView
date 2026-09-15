[CmdletBinding()]
param([ValidateSet("backend", "frontend")][string]$Service = "backend", [switch]$ErrorLog)

$ServerRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$suffix = if ($ErrorLog) { ".err.log" } else { ".log" }
$path = Join-Path $ServerRoot "runtime\logs\$Service$suffix"
if (-not (Test-Path $path)) { throw "尚未生成日志：$path" }
Get-Content -LiteralPath $path -Tail 200 -Wait
