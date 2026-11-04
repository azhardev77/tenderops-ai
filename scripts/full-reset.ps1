# TenderOps AI - full local reset: stops the stack, drops data volumes + file storage.
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
  Write-Host "full-reset REFUSED: TENDEROPS_ENV=prod (or a prod profile is active). This script never touches prod."
  exit 3
}
if (Get-Command docker -ErrorAction SilentlyContinue) {
  & docker compose down -v
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} else {
  Write-Host "docker not found: skipping compose teardown (native services, if any, keep running)."
}
$storage = Join-Path $Root "data\storage"
if (Test-Path $storage) {
  Get-ChildItem $storage -Force | Remove-Item -Recurse -Force
  Write-Host "Cleared $storage"
}
Write-Host "full-reset complete (non-prod)."
