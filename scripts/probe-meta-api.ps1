# TenderOps AI - probe the Meta Model API (at most 10 minimal real calls).
# Sends the key ONLY to https://api.meta.ai/v1 (refuses any other host).
# Prints status codes only. The API key is NEVER written to logs or console output.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
New-Item -ItemType Directory -Force -Path "$Root\docs\test-logs" | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$log = "$Root\docs\test-logs\probe-meta-api-$stamp.log"
$modelKey = ""
$apiBase = "https://api.meta.ai/v1"
if (Test-Path "$Root\.env") {
  Get-Content "$Root\.env" | ForEach-Object {
    $line = $_.Trim()
    if ($line.StartsWith("MODEL_API_KEY=")) { $modelKey = $line.Substring(14).Trim() }
    if ($line.StartsWith("META_API_BASE_URL=")) { $v = $line.Substring(18).Trim(); if ($v -ne "") { $apiBase = $v } }
  }
}
function Write-Probe($msg) {
  $msg | Tee-Object -FilePath $log -Append | Out-Null
  Write-Host $msg
}
$probeUri = $null
if (-not [System.Uri]::TryCreate($apiBase, [System.UriKind]::Absolute, [ref]$probeUri) -or $probeUri.Host -ne "api.meta.ai") {
  Write-Probe "REFUSED: the probe sends the key only to https://api.meta.ai/v1"
  exit 2
}
if ([string]::IsNullOrEmpty($modelKey)) {
  Write-Probe "MODEL_API_KEY is empty: no live probe possible. Build to the claimed shape against test doubles (FALLBACK). LIVE was not tested."
  exit 2
}
$calls = 0
function Invoke-Probe($name, $method, $url, $body) {
  if ($script:calls -ge 10) { Write-Probe "${name}: SKIPPED (10-call budget exhausted)"; return }
  $script:calls++
  $headers = @{ Authorization = ("Bearer " + $modelKey) }
  try {
    if ($body -eq $null) { $r = Invoke-WebRequest -UseBasicParsing -Method $method -Uri $url -Headers $headers -TimeoutSec 30 }
    else { $r = Invoke-WebRequest -UseBasicParsing -Method $method -Uri $url -Headers $headers -ContentType "application/json" -Body $body -TimeoutSec 30 }
    Write-Probe ("${name}: status=" + $r.StatusCode)
  }
  catch {
    $status = "ERROR"
    try { $status = [string]$_.Exception.Response.StatusCode.value__ } catch { }
    Write-Probe ("${name}: status=" + $status)
  }
}
Write-Probe ("probe start time=" + (Get-Date -Format o))
Invoke-Probe "models-list" "Get" ($apiBase + "/models") $null
Invoke-Probe "muse-text" "Post" ($apiBase + "/responses") '{"model":"muse-spark-1.3","input":"Reply with the single word OK."}'
Invoke-Probe "muse-structured" "Post" ($apiBase + "/responses") '{"model":"muse-spark-1.3","input":"Return JSON.","text":{"format":{"type":"json_schema","name":"ok","strict":true,"schema":{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}}}}'
Invoke-Probe "muse-tools" "Post" ($apiBase + "/responses") '{"model":"muse-spark-1.3","input":"What is 2+2? Use the tool if available.","tools":[{"type":"function","name":"add","description":"Add two numbers","parameters":{"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"}},"required":["a","b"],"additionalProperties":false}}]}'
$tinyPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
Invoke-Probe "muse-image" "Post" ($apiBase + "/responses") ('{"model":"muse-spark-1.3","input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"Describe this image in one word."},{"type":"input_image","image_url":"data:image/png;base64,' + $tinyPng + '"}]}]}')
Invoke-Probe "sam-segment" "Post" ($apiBase + "/responses") ('{"model":"sam-3.1","input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"circular official stamp"},{"type":"input_image","image_url":"data:image/png;base64,' + $tinyPng + '"}]}]}')
Write-Probe ("probe done calls=" + $script:calls + " (budget 10). Key never logged.")
