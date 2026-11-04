# TenderOps AI - full verification from clean (logs to docs/test-logs/). Exits non-zero on any failure.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
New-Item -ItemType Directory -Force -Path "$Root\docs\test-logs" | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$failed = $false
Write-Host "=== server: mvn clean test ==="
$serverLog = "$Root\docs\test-logs\verify-server-$stamp.log"
& mvn -B -f "$Root\server\pom.xml" clean test > $serverLog 2>&1
if ($LASTEXITCODE -ne 0) { $failed = $true; Write-Host "SERVER FAILED (see $serverLog)"; Get-Content -Tail 40 $serverLog } else { Write-Host "server: passed" }
Write-Host "=== client: npm ci ==="
Set-Location "$Root\client"
$ciLog = "$Root\docs\test-logs\verify-client-ci-$stamp.log"
& npm ci --no-audit --no-fund > $ciLog 2>&1
if ($LASTEXITCODE -ne 0) { $failed = $true; Write-Host "npm ci FAILED (see $ciLog)"; Get-Content -Tail 40 $ciLog } else { Write-Host "npm ci: passed" }
Write-Host "=== client: npm run build ==="
$buildLog = "$Root\docs\test-logs\verify-client-build-$stamp.log"
& npm run build > $buildLog 2>&1
if ($LASTEXITCODE -ne 0) { $failed = $true; Write-Host "BUILD FAILED (see $buildLog)"; Get-Content -Tail 40 $buildLog } else { Write-Host "build: passed" }
Write-Host "=== client: npm test ==="
$testLog = "$Root\docs\test-logs\verify-client-test-$stamp.log"
& npm test > $testLog 2>&1
if ($LASTEXITCODE -ne 0) { $failed = $true; Write-Host "CLIENT TESTS FAILED (see $testLog)"; Get-Content -Tail 40 $testLog } else { Write-Host "client tests: passed" }
Set-Location $Root
Write-Host "=== secret sweep ==="
& "$Root\scripts\secret-sweep.ps1"
if ($LASTEXITCODE -ne 0) { $failed = $true; Write-Host "SECRET SWEEP FAILED" } else { Write-Host "secret sweep: passed" }
if ($failed) { Write-Host "VERIFY-ALL: FAILED"; exit 1 } else { Write-Host "VERIFY-ALL: PASSED" }
