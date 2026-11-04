# TenderOps AI - stop the local stack (keeps data volumes).
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
& docker compose down
exit $LASTEXITCODE
