# TenderOps AI - stop the native server started by scripts/start-server (pid file only).
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
& taskkill /PID $pidValue | Out-Null
$deadline = (Get-Date).AddSeconds(15)
while ((Get-Date) -lt $deadline) {
  if (-not (Get-Process -Id $pidValue -ErrorAction SilentlyContinue)) { break }
  Start-Sleep -Milliseconds 500
}
if (Get-Process -Id $pidValue -ErrorAction SilentlyContinue) {
  & taskkill /F /PID $pidValue | Out-Null
}
Remove-Item $PidFile -Force
Write-Host "stopped pid $pidValue"
