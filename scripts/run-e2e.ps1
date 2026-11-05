# TenderOps AI - run the Playwright UI golden flow (TC-E2E-02) end to end:
# loads .env into the process environment (never prints values), resets demo
# state, starts server + client, waits for health, runs Playwright, copies the
# passing run's video to docs/demo-assets/golden-flow.webm, then stops everything.
# Usage: scripts/run-e2e.ps1 [playwright args...]
param([string[]]$PlaywrightArgs = @())
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
# The JVM derives java.io.tmpdir from TMP/TEMP; a restricted account may not be
# able to write its own temp dir, so point temp at the gitignored .run dir.
$runTmp = "$Root\.run\tmp"
New-Item -ItemType Directory -Force -Path $runTmp | Out-Null
[Environment]::SetEnvironmentVariable("TMP", $runTmp, "Process")
[Environment]::SetEnvironmentVariable("TEMP", $runTmp, "Process")
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
$demoPwd = [Environment]::GetEnvironmentVariable("DEMO_ADMIN_PASSWORD", "Process")
if ([string]::IsNullOrEmpty($demoPwd)) {
  Write-Host "run-e2e failed: DEMO_ADMIN_PASSWORD is empty (check .env; value never printed)"
  exit 1
}
[Environment]::SetEnvironmentVariable("E2E_DEMO_PASSWORD", $demoPwd, "Process")
& "$Root\scripts\reset-demo.ps1"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
try {
  if (-not (Test-Path "$Root\client\node_modules\@playwright\test")) {
    Write-Host "run-e2e failed: @playwright/test is not installed; run 'npm ci' in client/ first"
    exit 1
  }
  New-Item -ItemType Directory -Force -Path "$Root\.run" | Out-Null
  New-Item -ItemType Directory -Force -Path "$Root\docs\test-logs" | Out-Null
  # Node tools (vite, playwright) are launched from the drive root with absolute
  # paths: esbuild walks up from CWD and fails hard on an unreadable parent, so
  # a restricted checkout must not be the working directory. Same servers, same
  # config and root as `npm run dev`; behaves identically in a normal checkout.
  $driveRoot = (Split-Path -Qualifier $Root) + '\'
  $clientLog = "$Root\docs\test-logs\client-e2e.log"
  $clientPidFile = "$Root\.run\client.pid"
  if (Test-Path $clientPidFile) { Remove-Item $clientPidFile -Force }
  $viteJs = "$Root\client\node_modules\vite\bin\vite.js"
  $client = Start-Process -FilePath "node" `
    -ArgumentList "`"$viteJs`"", "`"$Root\client`"", "--config", "`"$Root\client\vite.config.ts`"" `
    -WorkingDirectory $driveRoot -RedirectStandardOutput $clientLog `
    -RedirectStandardError "$Root\docs\test-logs\client-e2e.err" `
    -WindowStyle Hidden -PassThru
  $client.Id | Out-File -Encoding ascii $clientPidFile
  $deadline = (Get-Date).AddMinutes(5)
  for (;;) {
    try {
      $page = Invoke-WebRequest -Uri "http://localhost:5173/" -TimeoutSec 5 -UseBasicParsing
      if ($page.StatusCode -eq 200) { break }
    } catch { Start-Sleep -Seconds 3 }
    if ((Get-Date) -gt $deadline) {
      Write-Host "client did not become healthy; see $clientLog"
      exit 1
    }
  }
  Write-Host "client UP pid $($client.Id) http://localhost:5173 (log $clientLog)"
  $pwBrowsers = Join-Path $Root ".pw-browsers"
  if (Test-Path $pwBrowsers) {
    [Environment]::SetEnvironmentVariable("PLAYWRIGHT_BROWSERS_PATH", $pwBrowsers, "Process")
  }
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $e2eLog = "$Root\docs\test-logs\e2e-$stamp.log"
  Write-Host "=== playwright (log $e2eLog) ==="
  Push-Location $driveRoot
  try {
    & node "$Root\client\node_modules\@playwright\test\cli.js" test `
      --config "$Root\client\playwright.config.ts" @PlaywrightArgs 2>&1 | Tee-Object -FilePath $e2eLog
    $code = $LASTEXITCODE
  } finally {
    Pop-Location
  }
  Set-Location $Root
  if ($code -eq 0) {
    $video = Get-ChildItem "$Root\client\test-results" -Recurse -Filter *.webm -ErrorAction SilentlyContinue |
      Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($video) {
      New-Item -ItemType Directory -Force -Path "$Root\docs\demo-assets" | Out-Null
      Copy-Item $video.FullName "$Root\docs\demo-assets\golden-flow.webm" -Force
      $mb = [math]::Round($video.Length / 1MB, 2)
      Write-Host "video: docs/demo-assets/golden-flow.webm ($mb MB)"
    } else {
      Write-Host "WARNING: playwright passed but no .webm found under client/test-results"
    }
  } else {
    Write-Host "PLAYWRIGHT FAILED (exit $code; see $e2eLog)"
  }
  exit $code
} finally {
  Set-Location $Root
  $clientPidFile = "$Root\.run\client.pid"
  if (Test-Path $clientPidFile) {
    $cid = (Get-Content $clientPidFile -Raw).Trim()
    if ($cid -match '^\d+$' -and (Get-Process -Id $cid -ErrorAction SilentlyContinue)) {
      & taskkill /T /PID $cid | Out-Null
      Start-Sleep -Seconds 2
      if (Get-Process -Id $cid -ErrorAction SilentlyContinue) { & taskkill /F /T /PID $cid | Out-Null }
    }
    Remove-Item $clientPidFile -Force
    Write-Host "client stopped"
  }
  & "$Root\scripts\stop-server.ps1" | Out-Null
  Write-Host "run-e2e cleanup done"
}
