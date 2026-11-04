# TenderOps AI - TC-OPS-04: run the golden flow against a FRESH company and verify
# LIVE aiMode per component. Without MODEL_API_KEY prints SKIP (exit 0).
# Requires: server running (scripts/dev-up or run-server).
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$base = "http://localhost:8080"
$modelKey = ""
if (Test-Path "$Root\.env") {
  Get-Content "$Root\.env" | ForEach-Object {
    $line = $_.Trim()
    if ($line.StartsWith("MODEL_API_KEY=")) { $modelKey = $line.Substring(14).Trim() }
  }
}
if ([string]::IsNullOrEmpty($modelKey)) {
  Write-Host "SKIP: MODEL_API_KEY is empty; LIVE mode is impossible. Rerun with a key for TC-OPS-04."
  exit 0
}
try {
  $stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
  $regBody = @{ email = "livecheck-$stamp@tenderops.local"; password = "livecheck-123456"; companyName = "LiveCheck Co" } | ConvertTo-Json
  $registered = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/register" -ContentType "application/json" -Body $regBody -TimeoutSec 60
  $headers = @{ Authorization = ("Bearer " + $registered.accessToken) }
  $start = Get-Date
  $loaded = Invoke-RestMethod -Method Post -Uri "$base/api/v1/demo/load-rfp" -Headers $headers -TimeoutSec 60
  $deadline = (Get-Date).AddMinutes(10)
  while ((Get-Date) -lt $deadline) {
    $job = Invoke-RestMethod -Uri "$base/api/v1/jobs/$($loaded.jobId)" -Headers $headers -TimeoutSec 30
    if ($job.status -eq "COMPLETED") { break }
    if ($job.status -eq "FAILED") { throw ("job failed: " + $job.error) }
    Start-Sleep -Seconds 5
  }
  $wallMs = [long]((Get-Date) - $start).TotalMilliseconds
  $matrix = Invoke-RestMethod -Uri "$base/api/v1/tender-versions/$($loaded.versionId)/matrix" -Headers $headers -TimeoutSec 60
  Write-Host "LIVE CHECK RESULT (fresh company, wall ${wallMs}ms)"
  Write-Host ("MUSE aiMode: " + $matrix.museMode)
  Write-Host ("SAM aiMode: " + $matrix.samMode)
  Write-Host ("decision: " + $matrix.decision + " score: " + $matrix.readinessScore)
  Write-Host "clause verdicts:"
  foreach ($row in $matrix.rows) {
    Write-Host ("  " + $row.requirement.clauseRef + " " + $row.verdict + " " + $row.reasonCode)
  }
  $failed = $false
  if ($matrix.museMode -ne "LIVE") { Write-Host "FAIL: MUSE aiMode is not LIVE"; $failed = $true }
  if ($matrix.samMode -ne "LIVE") { Write-Host "FAIL: SAM aiMode is not LIVE"; $failed = $true }
  if ($wallMs -gt 600000) { Write-Host "FAIL: wall time over 600s"; $failed = $true }
  Write-Host "Per-component p50 latencies: read ai_call_logs (UNVERIFIED here; no API exposes them)."
  if ($failed) { exit 1 }
  Write-Host "TC-OPS-04: PASS"
}
catch {
  Write-Host ("live-check failed: " + $_.Exception.Message)
  exit 1
}
