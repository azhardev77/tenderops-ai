# TenderOps AI - key presence check. Prints ONLY present/absent, never the value.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$present = $false
if (Test-Path "$Root\.env") {
  Get-Content "$Root\.env" | ForEach-Object {
    $line = $_.Trim()
    if ($line.StartsWith("MODEL_API_KEY=") -and $line.Substring(14).Trim() -ne "") {
      $present = $true
    }
  }
}
if ($present) { Write-Host "MODEL_API_KEY: present" } else { Write-Host "MODEL_API_KEY: absent" }
