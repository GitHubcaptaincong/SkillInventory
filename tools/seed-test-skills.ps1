param(
    [string]$BaseUrl = "http://127.0.0.1:8088",
    [switch]$SkipPublish
)

$ErrorActionPreference = "Stop"
$fixtureDir = Join-Path $PSScriptRoot "..\test-fixtures\skills"
$files = Get-ChildItem -LiteralPath $fixtureDir -Filter *.json | Sort-Object Name

foreach ($file in $files) {
    $payload = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json
    $body = $payload | ConvertTo-Json -Depth 5
    try {
        $created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/admin/skills" -ContentType "application/json" -Body $body
        Write-Host "Created $($payload.name) (id=$($created.id))"
        if (-not $SkipPublish) {
            $publishBody = @{ lockVersion = [long]$created.lockVersion } | ConvertTo-Json
            Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/admin/skills/$($created.id)/publish" -ContentType "application/json" -Body $publishBody | Out-Null
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
