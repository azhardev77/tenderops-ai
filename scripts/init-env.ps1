# TenderOps AI - create .env with CSPRNG secrets (the ONLY way to create it).
# Usage: scripts/init-env.ps1 [-OutFile .env]
# Writes MODEL_API_KEY (empty unless already in the process environment),
# JWT_SECRET (48 chars), DEMO_ADMIN_PASSWORD (20 chars), META_API_BASE_URL.
# Refuses to overwrite an existing file. Prints key NAMES only, never values.
param([string]$OutFile = "")
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
if ($OutFile -eq "") { $OutFile = "$Root\.env" }
if (Test-Path $OutFile) {
  Write-Host "REFUSED: $OutFile already exists (remove it yourself to regenerate)"
  exit 2
}
Add-Type -AssemblyName System.Security
function New-Secret([int]$length) {
  $alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
  $bytes = New-Object byte[] $length
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  $rng.GetBytes($bytes)
  $rng.Dispose()
  $chars = New-Object char[] $length
  for ($i = 0; $i -lt $length; $i++) { $chars[$i] = $alphabet[$bytes[$i] % 62] }
  return (-join $chars)
}
$modelKey = [Environment]::GetEnvironmentVariable("MODEL_API_KEY", "Process")
if ($modelKey -eq $null) { $modelKey = "" }
$lines = @(
  "MODEL_API_KEY=$modelKey",
  "JWT_SECRET=$(New-Secret 48)",
  "DEMO_ADMIN_PASSWORD=$(New-Secret 20)",
  "META_API_BASE_URL=https://api.meta.ai/v1"
)
$lines | Out-File -Encoding ascii -FilePath $OutFile
Write-Host "wrote $OutFile with keys: MODEL_API_KEY,JWT_SECRET,DEMO_ADMIN_PASSWORD,META_API_BASE_URL"
