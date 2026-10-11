param(
    [Parameter(Mandatory = $true)][string]$PostgresBin,
    [ValidateRange(1024, 65535)][int]$Port = 55439
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$bin = (Resolve-Path -LiteralPath $PostgresBin).Path
foreach ($name in @('initdb.exe', 'pg_ctl.exe', 'pg_dump.exe', 'pg_restore.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $bin $name))) { throw "缺少 PostgreSQL 工具：$name" }
}
$labRoot = Join-Path (Split-Path $repository -Parent) '.iview-runtime\postgres-rehearsals'
$lab = Join-Path $labRoot ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $lab -Force | Out-Null
$cluster = Join-Path $lab 'cluster'
$passwordFile = Join-Path $lab 'init-password.txt'
$labPassword = [Guid]::NewGuid().ToString('N')
[IO.File]::WriteAllText($passwordFile, $labPassword, [Text.UTF8Encoding]::new($false))
$envNames = @('IVIEW_PG_REHEARSAL', 'IVIEW_PG_REHEARSAL_ADMIN_URL', 'IVIEW_PG_REHEARSAL_USER', 'IVIEW_PG_REHEARSAL_PASSWORD', 'IVIEW_PG_DUMP', 'IVIEW_PG_RESTORE')
$previousEnv = @{}
foreach ($name in $envNames) { $previousEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$started = $false
function Invoke-LabTool([string]$Tool, [string[]]$ToolArguments) {
    # Start-Process -Wait also waits for the postgres child server on Windows.
    # WaitForExit observes only the short-lived initdb/pg_ctl process.
    $process = Start-Process -FilePath (Join-Path $bin $Tool) -ArgumentList $ToolArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $lab "$Tool.out.log") -RedirectStandardError (Join-Path $lab "$Tool.err.log")
    if (-not $process.WaitForExit(60000)) { $process.Kill(); throw "$Tool 等待超时，日志目录：$lab" }
    if ($process.ExitCode -ne 0) { throw "$Tool 失败，退出码 $($process.ExitCode)，日志目录：$lab" }
}
try {
    Invoke-LabTool 'initdb.exe' @('-D', "`"$cluster`"", '-U', 'iview_rehearsal', '-E', 'UTF8', '--locale=C', '--auth=scram-sha-256', "--pwfile=`"$passwordFile`"")
    Remove-Item -LiteralPath $passwordFile
    Invoke-LabTool 'pg_ctl.exe' @('-D', "`"$cluster`"", '-l', "`"$(Join-Path $lab 'postgres.log')`"", '-o', "`"-h 127.0.0.1 -p $Port`"", '-w', '-t', '30', 'start')
    $started = $true
    $env:IVIEW_PG_REHEARSAL = 'true'
    $env:IVIEW_PG_REHEARSAL_ADMIN_URL = "jdbc:postgresql://127.0.0.1:$Port/postgres"
    $env:IVIEW_PG_REHEARSAL_USER = 'iview_rehearsal'
    $env:IVIEW_PG_REHEARSAL_PASSWORD = $labPassword
    $env:IVIEW_PG_DUMP = Join-Path $bin 'pg_dump.exe'
    $env:IVIEW_PG_RESTORE = Join-Path $bin 'pg_restore.exe'
    Push-Location $repository
    try {
        & '.\gradlew.bat' ':apps:server:test' '--tests' '*PostgresRestoreIntegrationTest' '--rerun-tasks' '--console=plain' '-q'
        if ($LASTEXITCODE -ne 0) { throw "PostgreSQL 恢复演练测试失败，日志目录：$lab" }
    } finally { Pop-Location }
    $report = Join-Path $repository 'apps\server\build\test-results\test\TEST-ai.moying.iview.backup.PostgresRestoreIntegrationTest.xml'
    [xml]$result = Get-Content -LiteralPath $report -Raw
    if ([int]$result.testsuite.tests -ne 1 -or [int]$result.testsuite.failures -ne 0 -or [int]$result.testsuite.errors -ne 0 -or [int]$result.testsuite.skipped -ne 0) { throw '验收未实际执行或未通过，请检查测试报告' }
    Copy-Item -LiteralPath $report -Destination (Join-Path $lab 'restore-test.xml')
    Write-Output "PostgreSQL 隔离恢复演练通过；报告：$lab\restore-test.xml"
} finally {
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $previousEnv[$name], 'Process') }
    # Only the cluster initialized by this invocation is stopped. Files remain available for audit.
    if ($started -and (Test-Path -LiteralPath (Join-Path $cluster 'postmaster.pid'))) { Invoke-LabTool 'pg_ctl.exe' @('-D', "`"$cluster`"", '-w', '-t', '30', '-m', 'fast', 'stop') }
    if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile }
}
