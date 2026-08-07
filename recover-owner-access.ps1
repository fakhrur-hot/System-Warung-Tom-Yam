# Sri Pantai Timur — deterministic owner-access recovery.
# Every step verifies itself and STOPS LOUDLY on failure (the previous script hid REST errors).
# Ends with owner-key.png on your Desktop: scan it with the phone (Setup -> Owner QR -> camera).

$ErrorActionPreference = "Stop"

$proj = "https://zpvlmvpodxuvwxnzurpi.supabase.co"
$anon = "sb_publishable_u_IHjLKynqTjkF4vPRo9yw_b-hh7biB"
$svc  = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpwdmxtdnBvZHh1dnd4bnp1cnBpIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc4NTk0MjcxMywiZXhwIjoyMTAxNTE4NzEzfQ.zVhfSoRawSJwS4K6rYCWiTuWJ5SWFmNwvD7k5aenGcE"
$hdr  = @{ apikey = $svc; Authorization = "Bearer $svc" }

Write-Host "[1/6] Current owner-key rows:"
(Invoke-WebRequest -Uri "$proj/rest/v1/settings?key=like.owner*&select=key,value" -Headers $hdr -UseBasicParsing).Content

# Mint 32 random bytes -> 64-hex key (same shape the server's generateToken(32) makes)
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$token = ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""
$sha   = [Security.Cryptography.SHA256]::Create()
$hash  = ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($token)) | ForEach-Object { $_.ToString("x2") }) -join ""

Write-Host "[2/6] Writing new hash..."
$resp = Invoke-WebRequest -Method Post -Uri "$proj/rest/v1/settings" `
  -Headers ($hdr + @{ Prefer = "resolution=merge-duplicates"; "Content-Type" = "application/json" }) `
  -Body "[{""key"":""owner_recovery_token_hash"",""value"":""$hash""}]" -UseBasicParsing
Write-Host ("      upsert HTTP " + [int]$resp.StatusCode)

Write-Host "[3/6] Deleting stale plaintext row (if any)..."
Invoke-WebRequest -Method Delete -Uri "$proj/rest/v1/settings?key=eq.owner_recovery_token" -Headers $hdr -UseBasicParsing | Out-Null

Write-Host "[4/6] Reading back to verify the hash landed..."
$rows = (Invoke-WebRequest -Uri "$proj/rest/v1/settings?key=eq.owner_recovery_token_hash&select=value" -Headers $hdr -UseBasicParsing).Content | ConvertFrom-Json
if ($rows.Count -eq 1 -and $rows[0].value -eq $hash) {
  Write-Host "      HASH VERIFIED IN DATABASE."
} else {
  Write-Host "      *** WRITE FAILED - stored value does not match. Stop and report this output. ***"
  Write-Host ($rows | ConvertTo-Json)
  exit 1
}

Write-Host "[5/6] End-to-end test: asking admin-recovery to accept the new key..."
try {
  $body = @{ recoveryToken = $token; deviceId = "pc-verify-delete-me"; deviceModel = "PC" } | ConvertTo-Json
  $r = Invoke-WebRequest -Method Post -Uri "$proj/functions/v1/admin-recovery" `
    -Headers @{ apikey = $anon; "Content-Type" = "application/json" } -Body $body -UseBasicParsing
  Write-Host ("      SERVER ACCEPTS THE KEY (HTTP " + [int]$r.StatusCode + "). Revoke device 'pc-verify-delete-me' later in Devices & Staff.")
} catch {
  Write-Host ("      *** SERVER REJECTED THE KEY: HTTP " + [int]$_.Exception.Response.StatusCode + " - stop and report this. ***")
  exit 1
}

Write-Host "[6/6] Rendering the QR to your Desktop..."
$api = [uri]::EscapeDataString($proj)
$key = [uri]::EscapeDataString($anon)
$url = "https://sri-pantai-timur.pages.dev/join?recover=$token&api=$api&key=$key"
$png = Join-Path ([Environment]::GetFolderPath("Desktop")) "owner-key.png"
node "$PSScriptRoot\qr-gen.mjs" $url $png
Write-Host ""
Write-Host "DONE. Scan Desktop\owner-key.png with the phone: Setup -> Owner QR -> scan/load."
Write-Host "That PNG is now the cafe's ONLY key - keep it like a house key."
Start-Process $png
