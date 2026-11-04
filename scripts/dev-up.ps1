# TenderOps AI - start the local stack (postgres + server + client).
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
if (Test-Path "$Root\.env") {
  Get-Content "$Root\.env" | ForEach-Object {
    $line = $_.Trim()
    if ($line -ne "" -and -not $line.StartsWith("#") -and $line.Contains("=")) {
      $name = $line.Substring(0, $line.IndexOf("=")).Trim()
      $value = $line.Substring($line.IndexOf("=") + 1).Trim()
      [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
  }
}
& docker compose up --build -d
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Waiting for the server..."
$deadline = (Get-Date).AddMinutes(5)
while ((Get-Date) -lt $deadline) {
  try {
    $health = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 5
    if ($health.status -eq "UP") { break }
  } catch { Start-Sleep -Seconds 5 }
}
try {
  $health = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 5
  if ($health.status -ne "UP") { throw "server not UP" }
} catch {
  Write-Host "Server did not become healthy in time. Logs:"
  & docker compose logs --tail 50 server
  exit 1
}
Write-Host "UP: client http://localhost:3000  server http://localhost:8080  swagger http://localhost:8080/swagger-ui/index.html"
