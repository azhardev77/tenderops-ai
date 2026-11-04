# TenderOps AI - seed the demo flow on a running stack: login as the demo admin,
# load the bundled RFP, wait for the pipeline, print the decision.
# Requires: server running (scripts/dev-up), DEMO_ADMIN_PASSWORD in .env.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$base = "http://localhost:8080"
$demoPwd = ""
if (Test-Path "$Root\.env") {
  Get-Content "$Root\.env" | ForEach-Object {
    $line = $_.Trim()
    if ($line.StartsWith("DEMO_ADMIN_PASSWORD=")) { $demoPwd = $line.Substring(20).Trim() }
  }
}
if ([string]::IsNullOrEmpty($demoPwd)) {
  Write-Host "seed-demo failed: DEMO_ADMIN_PASSWORD is empty in .env"
  exit 1
}
try {
  $loginBody = @{ email = "demo@tenderops.local"; password = $demoPwd } | ConvertTo-Json
  $login = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" -ContentType "application/json" -Body $loginBody -TimeoutSec 30
  $headers = @{ Authorization = ("Bearer " + $login.accessToken) }
  $loaded = Invoke-RestMethod -Method Post -Uri "$base/api/v1/demo/load-rfp" -Headers $headers -TimeoutSec 60
  if ($loaded.duplicate) {
    Write-Host ("RFP already loaded: tender " + $loaded.tenderId + " version " + $loaded.versionId)
    exit 0
  }
  $deadline = (Get-Date).AddMinutes(10)
  while ((Get-Date) -lt $deadline) {
    $job = Invoke-RestMethod -Uri "$base/api/v1/jobs/$($loaded.jobId)" -Headers $headers -TimeoutSec 30
    if ($job.status -eq "COMPLETED") { break }
    if ($job.status -eq "FAILED") { throw ("job failed: " + $job.error) }
    Start-Sleep -Seconds 5
  }
  $matrix = Invoke-RestMethod -Uri "$base/api/v1/tender-versions/$($loaded.versionId)/matrix" -Headers $headers -TimeoutSec 60
  Write-Host ("Seeded: tender " + $loaded.tenderId + " version " + $loaded.versionId)
  Write-Host ("decision: " + $matrix.decision + " score: " + $matrix.readinessScore)
}
catch {
  Write-Host ("seed-demo failed: " + $_.Exception.Message)
  exit 1
}
