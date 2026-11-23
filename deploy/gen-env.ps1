# Tạo deploy/.env với khóa ngẫu nhiên; không ghi đè file đã có.
$ErrorActionPreference = 'Stop'
$target = Join-Path $PSScriptRoot '.env'
if (Test-Path $target) { throw 'deploy/.env đã tồn tại, không ghi đè' }
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
function New-Bytes([int]$n) { $b = New-Object byte[] $n; $rng.GetBytes($b); return ,$b }
$lines = Get-Content (Join-Path $PSScriptRoot '.env.example') -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^(LEARNINGE_INTERNAL_TOKEN|LEARNINGE_SERVER_KEY|LEARNINGE_PSEUDONYM_KEY)=$') {
        $_ + [Convert]::ToBase64String((New-Bytes 32))
    } elseif ($_ -match '^MONGO_\w+_PASSWORD=$') {
        $_ + (-join ((New-Bytes 24) | ForEach-Object { $_.ToString('x2') }))
    } else { $_ }
}
[System.IO.File]::WriteAllLines($target, [string[]]$lines, (New-Object System.Text.UTF8Encoding $false))
Write-Output 'Đã tạo deploy/.env'
