# TenderOps AI - reset demo state to a clean seeded stack.
# The dev profile uses an in-memory H2 database, so a server restart wipes all
# demo rows; this script also clears file storage, then starts the server fresh
# (which re-runs the seeder). Uses scripts/stop-server + scripts/start-server.
# REFUSES to run when TENDEROPS_ENV=prod or SPRING_PROFILES_ACTIVE contains prod.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
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
$envName = [Environment]::GetEnvironmentVariable("TENDEROPS_ENV", "Process")
$profiles = [Environment]::GetEnvironmentVariable("SPRING_PROFILES_ACTIVE", "Process")
if ($envName -eq "prod" -or ($profiles -ne $null -and $profiles.Contains("prod"))) {
  Write-Host "reset-demo REFUSED: TENDEROPS_ENV=prod (or a prod profile is active). This script never touches prod."
  exit 3
}
& "$Root\scripts\stop-server.ps1"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$storage = Join-Path $Root "data\storage"
if (Test-Path $storage) {
  Get-ChildItem $storage -Force | Remove-Item -Recurse -Force
  Write-Host "Cleared $storage"
}
& "$Root\scripts\start-server.ps1"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "reset-demo complete: clean server with fresh seeded demo data."
