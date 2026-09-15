$ErrorActionPreference = "Stop"
$ServerRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$WorkspaceRoot = (Resolve-Path (Join-Path $ServerRoot "..")).Path
$FrontendRoot = Join-Path $WorkspaceRoot "iView-frontend"
$node = (Get-Command node.exe -ErrorAction Stop).Source
if (-not $env:JAVA_HOME -and -not (Get-Command java.exe -ErrorAction SilentlyContinue)) { throw "请设置 JAVA_HOME（JDK 21）或将 java.exe 加入 PATH。" }
Push-Location $FrontendRoot
try { & $node ".\scripts\build.mjs"; if ($LASTEXITCODE -ne 0) { throw "前端构建失败" } } finally { Pop-Location }
Push-Location $ServerRoot
try { & ".\gradlew.bat" ":apps:server:bootJar"; if ($LASTEXITCODE -ne 0) { throw "后端构建失败" } } finally { Pop-Location }
Write-Host "构建完成。"
