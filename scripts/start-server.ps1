# TenderOps AI - run the server natively from the built fat jar (no Docker).
# Usage: scripts/start-server.ps1 [-Profile dev] [-Port 8080]
# .env values fill gaps only (explicit env/args win). Writes .run/server.pid,
# logs to docs/test-logs/server.log. Refuses a second instance.
param([string]$Profile = "", [int]$Port = 0)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
if ($Profile -ne "") { $env:SPRING_PROFILES_ACTIVE = $Profile }
if ($Port -ne 0) { $env:SERVER_PORT = "$Port" }
if ($env:SPRING_PROFILES_ACTIVE -eq $null -or $env:SPRING_PROFILES_ACTIVE -eq "") {
  $env:SPRING_PROFILES_ACTIVE = "dev"
}
if (Test-Path "$Root\.env") {
  Get-Content "$Root\.env" | ForEach-Object {
    $line = $_.Trim()
    if ($line -ne "" -and -not $line.StartsWith("#") -and $line.Contains("=")) {
      $name = $line.Substring(0, $line.IndexOf("=")).Trim()
      $value = $line.Substring($line.IndexOf("=") + 1).Trim()
      if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name, "Process"))) {
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
      }
    }
  }
}
$PidFile = "$Root\.run\server.pid"
if (Test-Path $PidFile) {
  $old = (Get-Content $PidFile -Raw).Trim()
  if ($old -match '^\d+$' -and (Get-Process -Id $old -ErrorAction SilentlyContinue)) {
    Write-Host "server already running (pid $old); use scripts/stop-server.ps1 first"
    exit 1
  }
  Remove-Item $PidFile -Force
}
$Jar = "$Root\server\target\tenderops-server-1.0.0.jar"
if (-not (Test-Path $Jar)) {
  Write-Host "missing $Jar; run mvn package in server/ first"
  exit 1
}
$java = "java"
if (-not [string]::IsNullOrEmpty($env:JAVA_HOME)) { $java = "$env:JAVA_HOME\bin\java.exe" }
New-Item -ItemType Directory -Force -Path "$Root\.run" | Out-Null
New-Item -ItemType Directory -Force -Path "$Root\docs\test-logs" | Out-Null
$port = "8080"
if (-not [string]::IsNullOrEmpty($env:SERVER_PORT)) { $port = $env:SERVER_PORT }
$log = "$Root\docs\test-logs\server.log"
$proc = Start-Process -FilePath $java -ArgumentList "-jar", $Jar -WorkingDirectory $Root `
  -RedirectStandardOutput $log -RedirectStandardError "$Root\docs\test-logs\server.err" `
  -WindowStyle Hidden -PassThru
$deadline = (Get-Date).AddMinutes(5)
for (;;) {
  try {
    $health = Invoke-RestMethod -Uri "http://localhost:$port/actuator/health" -TimeoutSec 5
    if ($health.status -eq "UP") { break }
  } catch { Start-Sleep -Seconds 5 }
  if ((Get-Date) -gt $deadline) {
    Write-Host "server did not become healthy; see $log"
    exit 1
  }
}
if (-not (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue)) {
  Write-Host ("server process " + $proc.Id + " died; a squatter may hold port " + $port + "; see " + $log)
  exit 1
}
$proc.Id | Out-File -Encoding ascii $PidFile
Write-Host "UP pid $($proc.Id) profile $env:SPRING_PROFILES_ACTIVE http://localhost:$port (log $log)"
