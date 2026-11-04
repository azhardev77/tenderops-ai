# TenderOps AI - remove ONLY build outputs: server/target, client/dist, .run.
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Split-Path -Parent $PSScriptRoot)).Path
foreach ($rel in @("server\target", "client\dist", ".run")) {
  $full = [IO.Path]::GetFullPath((Join-Path $Root $rel))
  if (-not $full.StartsWith($Root, [StringComparison]::OrdinalIgnoreCase)) {
    Write-Host "REFUSED: $rel escapes the project root"
    exit 2
  }
  if (Test-Path $full) {
    Remove-Item $full -Recurse -Force
    Write-Host "removed $rel"
  } else {
    Write-Host "absent $rel"
  }
}
