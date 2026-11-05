# TenderOps AI - stop the native server started by scripts/start-server (pid file only).
# The pid file holds the Windows pid; either stop script works.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$PidFile = "$Root\.run\server.pid"
if (-not (Test-Path $PidFile)) {
  Write-Host "server not running (no pid file)"
  exit 0
}
$pidValue = (Get-Content $PidFile -Raw).Trim()
if ($pidValue -notmatch '^\d+$') {
  Write-Host "bad pid file content; remove $PidFile manually after inspection"
  exit 2
}
$proc = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
if (-not $proc) {
  Remove-Item $PidFile -Force
  Write-Host "pid $pidValue already gone; pid file cleared"
  exit 0
}
if ($proc.ProcessName -notmatch '^(java|javaw)$') {
  Write-Host "REFUSED: pid $pidValue is $($proc.ProcessName), not the server"
  exit 2
}
cmd.exe /c "taskkill /PID $pidValue >nul 2>nul"
$deadline = (Get-Date).AddSeconds(5)
while ((Get-Date) -lt $deadline) {
  if (-not (Get-Process -Id $pidValue -ErrorAction SilentlyContinue)) { break }
  Start-Sleep -Milliseconds 500
}
if (Get-Process -Id $pidValue -ErrorAction SilentlyContinue) {
  cmd.exe /c "taskkill /PID $pidValue /T /F >nul 2>nul"
  Start-Sleep -Seconds 1
}
if (Get-Process -Id $pidValue -ErrorAction SilentlyContinue) {
  Write-Host "FAILED to stop pid $pidValue"
  exit 1
}
Remove-Item $PidFile -Force
Write-Host "stopped pid $pidValue"
