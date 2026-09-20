[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$WinSwPath,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NodePath = "",
    [ValidateRange(1024, 65535)][int]$BackendPort = 8080,
    [ValidateRange(1024, 65535)][int]$FrontendPort = 3000,
    [switch]$Start
)

$ErrorActionPreference = "Stop"
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw "安装 Windows 服务必须以管理员身份运行 PowerShell。" }
$ServerRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$WorkspaceRoot = (Resolve-Path (Join-Path $ServerRoot "..")).Path
$FrontendRoot = Join-Path $WorkspaceRoot "iView-frontend"
$ServiceRoot = Join-Path $ServerRoot "runtime\services"
$LogRoot = Join-Path $ServerRoot "runtime\logs"
$wrapper = (Resolve-Path -LiteralPath $WinSwPath).Path
if ([IO.Path]::GetExtension($wrapper) -ne ".exe") { throw "WinSwPath 必须指向 WinSW 可执行文件。" }
$java = if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\java.exe"))) { (Resolve-Path (Join-Path $JavaHome "bin\java.exe")).Path } else { (Get-Command java.exe -ErrorAction Stop).Source }
$node = if ($NodePath) { (Resolve-Path -LiteralPath $NodePath).Path } else { (Get-Command node.exe -ErrorAction Stop).Source }
$jar = Get-ChildItem (Join-Path $ServerRoot "apps\server\build\libs\*.jar") -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch "plain" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw "没有找到后端可执行 Jar，请先运行 Build-iView.ps1。" }
if (-not (Test-Path (Join-Path $FrontendRoot "dist\index.html"))) { throw "没有找到前端构建产物，请先运行 Build-iView.ps1。" }

New-Item -ItemType Directory -Force -Path $ServiceRoot, $LogRoot | Out-Null
function Escape-Xml([string]$value) { return [Security.SecurityElement]::Escape($value) }
function Write-Service([string]$id, [string]$name, [string]$executable, [string]$arguments, [string]$workingDirectory, [hashtable]$environment, [string]$dependency = "") {
    $exe = Join-Path $ServiceRoot "$id.exe"; $xml = Join-Path $ServiceRoot "$id.xml"
    Copy-Item -LiteralPath $wrapper -Destination $exe -Force
    $envXml = ($environment.GetEnumerator() | ForEach-Object { "  <env name=`"$(Escape-Xml $_.Key)`" value=`"$(Escape-Xml ([string]$_.Value))`" />" }) -join "`r`n"
    $dependXml = if ($dependency) { "  <depend>$(Escape-Xml $dependency)</depend>" } else { "" }
    @"
<service>
  <id>$(Escape-Xml $id)</id>
  <name>$(Escape-Xml $name)</name>
  <description>iView 工业数据与可视化平台</description>
  <executable>$(Escape-Xml $executable)</executable>
  <arguments>$(Escape-Xml $arguments)</arguments>
  <workingdirectory>$(Escape-Xml $workingDirectory)</workingdirectory>
$envXml
$dependXml
  <startmode>Automatic</startmode>
  <delayedAutoStart>true</delayedAutoStart>
  <onfailure action="restart" delay="10 sec" />
  <onfailure action="restart" delay="30 sec" />
  <resetfailure>1 hour</resetfailure>
  <logpath>$(Escape-Xml $LogRoot)</logpath>
  <log mode="roll-by-size-time">
    <sizeThreshold>20480</sizeThreshold>
    <pattern>yyyyMMdd</pattern>
    <autoRollAtTime>00:00:00</autoRollAtTime>
    <zipOlderThanNumDays>7</zipOlderThanNumDays>
    <zipDateFormat>yyyyMM</zipDateFormat>
    <keepFiles>30</keepFiles>
  </log>
</service>
"@ | Set-Content -LiteralPath $xml -Encoding utf8
    & $exe install
    if ($LASTEXITCODE -ne 0) { throw "服务 $id 安装失败。" }
}

Write-Service "iViewBackend" "iView Backend" $java "-jar `"$($jar.FullName)`"" $ServerRoot @{ SERVER_PORT = $BackendPort }
try {
    Write-Service "iViewFrontend" "iView Frontend" $node "server.mjs" $FrontendRoot @{ PORT = $FrontendPort; IVIEW_BACKEND_URL = "http://127.0.0.1:$BackendPort" } "iViewBackend"
} catch {
    & (Join-Path $ServiceRoot "iViewBackend.exe") uninstall | Out-Null
    throw
}
if ($Start) { & (Join-Path $ServiceRoot "iViewBackend.exe") start; & (Join-Path $ServiceRoot "iViewFrontend.exe") start }
Write-Host "iViewBackend 与 iViewFrontend 已注册为 Windows 服务。日志目录：$LogRoot"
