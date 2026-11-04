# TenderOps AI - fail-closed secret scan (called by scripts/verify-all).
# Scans tracked files + docs/ + docs/test-logs/ for the key pattern LLM_[0-9]+_
# and for the secret values in .env (MODEL_API_KEY, JWT_SECRET,
# DEMO_ADMIN_PASSWORD, PG_PASSWORD, PGADMIN_PASSWORD). Non-secret .env values
# (URLs, usernames, paths) legitimately appear in docs/compose and are NOT
# scanned. Prints ONLY PASS/FAIL, never values or file names. Exit 1 on FAIL.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$files = @()
try {
  $gitRoot = ($Root -replace '\\', '/')
  $listed = & git -c "safe.directory=$gitRoot" ls-files 2>$null
  if ($LASTEXITCODE -eq 0 -and $listed) { $files = $listed }
} catch { }
if ($files.Count -eq 0) {
  $files = Get-ChildItem server/src, client/src, client/e2e, scripts, docs -Recurse -File `
    -ErrorAction SilentlyContinue | ForEach-Object {
      $_.FullName.Substring($Root.Length + 1).Replace('\', '/') }
  foreach ($top in @("README.md", "MASTER_PROMPT.md", ".env.example", "docker-compose.yml",
      "Dockerfile.server", "Dockerfile.client", "nginx.conf")) {
    if (Test-Path $top) { $files += $top }
  }
}
$hit = $false
foreach ($f in $files) {
  $p = Join-Path $Root ($f -replace '/', '\')
  if (-not (Test-Path $p -PathType Leaf)) { continue }
  try { $text = [Text.Encoding]::Latin1.GetString([IO.File]::ReadAllBytes($p)) }
  catch { continue }
  if ($text -match 'LLM_[0-9]+_') { $hit = $true; break }
}
if (-not $hit -and (Test-Path "$Root\.env")) {
  $wanted = @("MODEL_API_KEY", "JWT_SECRET", "DEMO_ADMIN_PASSWORD", "PG_PASSWORD",
    "PGADMIN_PASSWORD")
  $secrets = @()
  Get-Content "$Root\.env" | ForEach-Object {
    $line = $_.Trim()
    if ($line -ne "" -and -not $line.StartsWith("#") -and $line.Contains("=")) {
      $name = $line.Substring(0, $line.IndexOf("=")).Trim()
      $value = $line.Substring($line.IndexOf("=") + 1).Trim()
      if ($wanted -contains $name -and $value.Length -ge 8) { $secrets += $value }
    }
  }
  foreach ($f in $files) {
    if ($hit) { break }
    $p = Join-Path $Root ($f -replace '/', '\')
    if (-not (Test-Path $p -PathType Leaf)) { continue }
    try { $text = [Text.Encoding]::Latin1.GetString([IO.File]::ReadAllBytes($p)) }
    catch { continue }
    foreach ($s in $secrets) {
      if ($text.Contains($s)) { $hit = $true; break }
    }
  }
}
if ($hit) { Write-Host "SECRET-SWEEP: FAIL"; exit 1 } else { Write-Host "SECRET-SWEEP: PASS" }
