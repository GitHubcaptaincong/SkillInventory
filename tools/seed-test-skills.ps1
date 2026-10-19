param(
    [string]$BaseUrl = "http://127.0.0.1:8088",
    [switch]$SkipPublish
)

$ErrorActionPreference = "Stop"
# Prompt interactively; never persist or print the password/session.
$credential = Get-Credential -Message "Skill Inventory administrator"
$csrf = Invoke-RestMethod -Uri "$BaseUrl/api/auth/csrf" -SessionVariable browser
$headers = @{ 'X-CSRF-TOKEN' = $csrf.token }
$login = @{ username = $credential.UserName; password = $credential.GetNetworkCredential().Password } | ConvertTo-Json
try {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -WebSession $browser -Headers $headers -ContentType "application/json" -Body $login | Out-Null
} finally { $login = $null; $credential = $null }
$csrf = Invoke-RestMethod -Uri "$BaseUrl/api/auth/csrf" -WebSession $browser
$headers = @{ 'X-CSRF-TOKEN' = $csrf.token }
$fixtureDir = Join-Path $PSScriptRoot "..\test-fixtures\skills"
$files = Get-ChildItem -LiteralPath $fixtureDir -Filter *.json | Sort-Object Name

foreach ($file in $files) {
    $payload = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json
    $body = $payload | ConvertTo-Json -Depth 5
    try {
        $created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/admin/skills" -WebSession $browser -Headers $headers -ContentType "application/json" -Body $body
        Write-Host "Created $($payload.name) (id=$($created.id))"
        if (-not $SkipPublish) {
            $publishBody = @{ lockVersion = [long]$created.lockVersion } | ConvertTo-Json
            Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/admin/skills/$($created.id)/publish" -WebSession $browser -Headers $headers -ContentType "application/json" -Body $publishBody | Out-Null
            Write-Host "Published $($payload.name)"
        }
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -eq 409 -or $status -eq 422) {
            Write-Warning "Skipped $($payload.name): API returned $status, likely already exists or payload is invalid."
            continue
        }
        throw
    }
}

Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/logout" -WebSession $browser -Headers $headers -ContentType "application/json" -Body "{}" | Out-Null
