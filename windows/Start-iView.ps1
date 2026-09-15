[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)][int]$BackendPort = 8080,
    [ValidateRange(1024, 65535)][int]$FrontendPort = 3000,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NodePath = "",
    [switch]$Build
)

$ErrorActionPreference = "Stop"
$ServerRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$WorkspaceRoot = (Resolve-Path (Join-Path $ServerRoot "..")).Path
$FrontendRoot = Join-Path $WorkspaceRoot "iView-frontend"
$RuntimeRoot = Join-Path $ServerRoot "runtime"
$LogRoot = Join-Path $RuntimeRoot "logs"
$PidRoot = Join-Path $RuntimeRoot "pids"

function Assert-PortFree([int]$Port) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) { throw "端口 $Port 已被进程 $($listener.OwningProcess) 占用。请先运行 Stop-iView.ps1，或指定其他端口。" }
}
function Find-Java {
    if ($JavaHome) {
        $candidate = Join-Path $JavaHome "bin\java.exe"
        if (Test-Path $candidate) { return $candidate }
        throw "JavaHome 未找到 java.exe：$JavaHome"
    }
    $bundled = Join-Path $WorkspaceRoot ".iview-runtime\jdk-21.0.12.1+1\bin\java.exe"
    if (Test-Path $bundled) { return $bundled }
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "未找到 Java 21。请设置 JAVA_HOME，或安装 JDK 21。"
}
function Find-Node {
    if ($NodePath) { if (Test-Path $NodePath) { return (Resolve-Path $NodePath).Path }; throw "NodePath 不存在：$NodePath" }
    $command = Get-Command node.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "未找到 Node.js。请安装 Node.js 20+，或通过 -NodePath 指定 node.exe。"
}
function Write-Pid([string]$Name, [int]$ProcessId) {
    $process = Get-Process -Id $ProcessId
    @{ id = $ProcessId; started_at = $process.StartTime.ToUniversalTime().ToString("O"); marker = "iview" } |
        ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $PidRoot "$Name.pid") -NoNewline -Encoding utf8
}

if (-not (Test-Path $FrontendRoot)) { throw "未找到前端目录：$FrontendRoot" }
New-Item -ItemType Directory -Force -Path $LogRoot, $PidRoot | Out-Null
& (Join-Path $PSScriptRoot "Stop-iView.ps1") -Quiet
Assert-PortFree $BackendPort; Assert-PortFree $FrontendPort

$jar = Get-ChildItem (Join-Path $ServerRoot "apps\server\build\libs\*.jar") -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch "plain" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($Build -or -not $jar) {
    & (Join-Path $PSScriptRoot "Build-iView.ps1")
    $jar = Get-ChildItem (Join-Path $ServerRoot "apps\server\build\libs\*.jar") | Where-Object { $_.Name -notmatch "plain" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
}
if (-not $jar) { throw "没有找到后端可执行 Jar。请先运行 Build-iView.ps1。" }

$java = Find-Java; $node = Find-Node
$backendLog = Join-Path $LogRoot "backend.log"; $backendError = Join-Path $LogRoot "backend.err.log"
$frontendLog = Join-Path $LogRoot "frontend.log"; $frontendError = Join-Path $LogRoot "frontend.err.log"
$previousServerPort = $env:SERVER_PORT; $previousBackendUrl = $env:IVIEW_BACKEND_URL; $previousFrontendPort = $env:PORT
try {
    $env:SERVER_PORT = "$BackendPort"
    $backend = Start-Process -FilePath $java -ArgumentList @("-jar", $jar.FullName) -WorkingDirectory $ServerRoot -WindowStyle Hidden -RedirectStandardOutput $backendLog -RedirectStandardError $backendError -PassThru
    Write-Pid "backend" $backend.Id
    $env:PORT = "$FrontendPort"; $env:IVIEW_BACKEND_URL = "http://127.0.0.1:$BackendPort"
    $frontend = Start-Process -FilePath $node -ArgumentList @("server.mjs") -WorkingDirectory $FrontendRoot -WindowStyle Hidden -RedirectStandardOutput $frontendLog -RedirectStandardError $frontendError -PassThru
    Write-Pid "frontend" $frontend.Id
} finally {
    $env:SERVER_PORT = $previousServerPort; $env:IVIEW_BACKEND_URL = $previousBackendUrl; $env:PORT = $previousFrontendPort
}

Start-Sleep -Seconds 2
Write-Host "iView 已启动。"
Write-Host "前端：http://127.0.0.1:$FrontendPort"
Write-Host "后端：http://127.0.0.1:$BackendPort/actuator/health"
Write-Host "日志：$LogRoot"
Write-Host "停止：powershell -ExecutionPolicy Bypass -File `"$PSScriptRoot\Stop-iView.ps1`""
