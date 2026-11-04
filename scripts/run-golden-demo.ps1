# TenderOps AI - full golden demo chain (judge walkthrough, API-driven).
# demo login -> load RFP -> matrix -> corrigendum -> matrix -> solvency doc+cert
# -> re-verify -> matrix -> approve -> PDF+XLSX downloads into .run/.
# Prints decisions/scores/verdicts at each stage (no secrets). Exit 1 only on
# infrastructure failure (HTTP/job); golden mismatches print GOLDEN-MISMATCH.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$transcript = "$Root\docs\test-logs\golden-demo-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".log"
Start-Transcript -Path $transcript | Out-Null
$script:failed = $false
$base = "http://localhost:8080"
$demoPwd = ""
if (Test-Path "$Root\.env") {
  Get-Content "$Root\.env" | ForEach-Object {
    $line = $_.Trim()
    if ($line.StartsWith("DEMO_ADMIN_PASSWORD=")) { $demoPwd = $line.Substring(20).Trim() }
  }
}
if ([string]::IsNullOrEmpty($demoPwd)) {
  Write-Host "golden-demo failed: DEMO_ADMIN_PASSWORD is empty in .env"
  exit 1
}
Add-Type -AssemblyName System.Net.Http
function Wait-Job($jobId, $headers, $label) {
  $deadline = (Get-Date).AddMinutes(10)
  while ((Get-Date) -lt $deadline) {
    $job = Invoke-RestMethod -Uri "$base/api/v1/jobs/$jobId" -Headers $headers -TimeoutSec 30
    if ($job.status -eq "COMPLETED") { return }
    if ($job.status -eq "FAILED") { throw ("job failed at " + $label + ": " + $job.error) }
    Start-Sleep -Seconds 5
  }
  throw ("job timed out at " + $label)
}
function Show-Matrix($versionId, $headers, $label) {
  $m = Invoke-RestMethod -Uri "$base/api/v1/tender-versions/$versionId/matrix" -Headers $headers -TimeoutSec 60
  Write-Host ("== " + $label + ": MUSE=" + $m.museMode + " SAM=" + $m.samMode + " decision=" + $m.decision + " score=" + $m.readinessScore)
  foreach ($row in $m.rows) {
    Write-Host ("  " + $row.requirement.clauseRef + " p" + $row.requirement.sourcePage + " " + $row.verdict + " " + $row.reasonCode)
  }
  return $m
}
function Check-Golden($actual, $expected, $label) {
  if ($actual -ne $expected) { Write-Host ("GOLDEN-MISMATCH " + $label + ": got " + $actual + " want " + $expected) }
}
try {
  $wall = Get-Date
  $loginBody = @{ email = "demo@tenderops.local"; password = $demoPwd } | ConvertTo-Json
  $login = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" -ContentType "application/json" -Body $loginBody -TimeoutSec 30
  $headers = @{ Authorization = ("Bearer " + $login.accessToken) }
  $loaded = Invoke-RestMethod -Method Post -Uri "$base/api/v1/demo/load-rfp" -Headers $headers -TimeoutSec 60
  Write-Host ("RFP: tender " + $loaded.tenderId + " version " + $loaded.versionId + " duplicate=" + $loaded.duplicate)
  if (-not $loaded.duplicate) { Wait-Job $loaded.jobId $headers "v1" }
  $m1 = Show-Matrix $loaded.versionId $headers "v1"
  Check-Golden $m1.decision "NO_BID" "v1.decision"
  Check-Golden $m1.readinessScore "40.00" "v1.score"
  if ($m1.timeSaved -ne $null) {
    Write-Host ("timeSaved: " + $m1.timeSaved.percent + "% (manual " + $m1.timeSaved.manualMinutes + " min, assisted " + $m1.timeSaved.assistedMinutes + " min)")
  } else { Write-Host "timeSaved: none" }
  $corr = Invoke-RestMethod -Method Post -Uri ($base + "/api/v1/demo/load-corrigendum?tenderId=" + $loaded.tenderId) -Headers $headers -TimeoutSec 60
  Write-Host ("corrigendum: version " + $corr.versionId + " duplicate=" + $corr.duplicate)
  if (-not $corr.duplicate) { Wait-Job $corr.jobId $headers "v2" }
  $m2 = Show-Matrix $corr.versionId $headers "v2"
  Check-Golden $m2.decision "CONDITIONAL_BID" "v2.decision"
  Check-Golden $m2.readinessScore "80.00" "v2.score"
  $v2run = $m2.runId
  Write-Host ("v2 runId: " + $v2run)
  $diff = Invoke-RestMethod -Uri ($base + "/api/v1/amendments/" + $corr.amendmentId + "/diff") -Headers $headers -TimeoutSec 30
  Write-Host ("amendment diff: " + ($diff | ConvertTo-Json -Compress -Depth 6))
  $delta = [decimal]$m2.readinessScore - [decimal]$m1.readinessScore
  Write-Host ("score delta v2-v1: " + $delta.ToString("0.00") + " (display only)")
  $client = New-Object System.Net.Http.HttpClient
  $client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $login.accessToken)
  $mp = New-Object System.Net.Http.MultipartFormDataContent
  $docBytes = [System.Text.Encoding]::UTF8.GetBytes("%PDF-1.4 demo solvency support")
  $part = New-Object System.Net.Http.ByteArrayContent(, $docBytes)
  $part.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/pdf")
  $mp.Add($part, "file", "solvency.pdf")
  $up = $client.PostAsync("$base/api/v1/company/documents", $mp).Result
  if (-not $up.IsSuccessStatusCode) { throw ("doc upload: " + $up.StatusCode) }
  $doc = ($up.Content.ReadAsStringAsync().Result | ConvertFrom-Json)
  $certBody = @{ name = "Bank Solvency Certificate"; issueDate = "2026-10-01"; expiryDate = "2027-03-31"; amount = "180000000"; documentId = $doc.id } | ConvertTo-Json
  $cert = Invoke-RestMethod -Method Post -Uri "$base/api/v1/company/certificates" -Headers ($headers + @{"Content-Type" = "application/json" }) -Body $certBody -TimeoutSec 30
  Write-Host ("solvency certificate: " + $cert.amount + " doc=" + $cert.documentId)
  $re = Invoke-RestMethod -Method Post -Uri ($base + "/api/v1/tender-versions/" + $corr.versionId + "/re-verify") -Headers $headers -TimeoutSec 60
  Wait-Job $re.jobId $headers "v3"
  $m3 = Show-Matrix $corr.versionId $headers "v3"
  Check-Golden $m3.decision "BID" "v3.decision"
  Check-Golden $m3.readinessScore "100.00" "v3.score"
  $dec = Invoke-RestMethod -Uri ($base + "/api/v1/tender-versions/" + $corr.versionId + "/decision") -Headers $headers -TimeoutSec 30
  Write-Host ("pre-approval status: " + $dec.approvalStatus)
  $apprBody = @{ decision = "APPROVED_FOR_BID" } | ConvertTo-Json
  try {
    $appr = Invoke-RestMethod -Method Post -Uri ($base + "/api/v1/tender-versions/" + $corr.versionId + "/decision/approve") -Headers ($headers + @{"Content-Type" = "application/json" }) -Body $apprBody -TimeoutSec 30
    Write-Host ("post-approval status: " + $appr.approvalStatus)
  } catch {
    $decided = Invoke-RestMethod -Uri ($base + "/api/v1/tender-versions/" + $corr.versionId + "/decision") -Headers $headers -TimeoutSec 30
    Write-Host ("post-approval status: " + $decided.approvalStatus + " (already decided)")
  }
  New-Item -ItemType Directory -Force -Path "$Root\.run" | Out-Null
  Invoke-RestMethod -Uri ($base + "/api/v1/tender-versions/" + $corr.versionId + "/reports/bid-package.pdf") -Headers $headers -OutFile "$Root\.run\golden-bid-package.pdf" -TimeoutSec 120
  Invoke-RestMethod -Uri ($base + "/api/v1/tender-versions/" + $corr.versionId + "/reports/checklist.xlsx") -Headers $headers -OutFile "$Root\.run\golden-checklist.xlsx" -TimeoutSec 120
  $pdfBytes = [System.IO.File]::ReadAllBytes("$Root\.run\golden-bid-package.pdf")
  $magic = [System.Text.Encoding]::ASCII.GetString($pdfBytes, 0, 5)
  Write-Host ("PDF bytes=" + $pdfBytes.Length + " magic=" + $magic)
  Write-Host ("XLSX bytes=" + (Get-Item "$Root\.run\golden-checklist.xlsx").Length)
  $pinned = Invoke-RestMethod -Uri ($base + "/api/v1/tender-versions/" + $corr.versionId + "/matrix?runId=" + $v2run) -Headers $headers -TimeoutSec 60
  Write-Host ("pinned v2-first-run: MUSE=" + $pinned.museMode + " SAM=" + $pinned.samMode + " decision=" + $pinned.decision + " score=" + $pinned.readinessScore)
  Write-Host ("GOLDEN-DEMO wall seconds: " + [long]((Get-Date) - $wall).TotalSeconds)
  Write-Host "GOLDEN-DEMO: DONE"
}
catch {
  Write-Host ("golden-demo failed: " + $_.Exception.Message)
  $script:failed = $true
}
Stop-Transcript | Out-Null
if ($script:failed) { exit 1 }
