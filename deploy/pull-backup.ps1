# Pulls the newest auth/sync backups from the VPS to this PC. Run daily with Task Scheduler. (ASCII only, see gen-env.ps1.)
#   powershell -ExecutionPolicy Bypass -File deploy/pull-backup.ps1 -Server user@api.example.com -Destination D:\LearningE-backup
param(
    [Parameter(Mandatory)][string]$Server,
    [Parameter(Mandatory)][string]$Destination,
    [int]$Keep = 30
)
$ErrorActionPreference = 'Stop'

function Invoke-Remote([string]$What, [scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$What failed ($LASTEXITCODE)" }
}

$target = Join-Path $Destination (Get-Date -Format 'yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Force $target | Out-Null
try {
    Invoke-Remote 'export' { ssh -o BatchMode=yes $Server 'bash /opt/LearningE_Server/deploy/export-backup.sh' }
    Invoke-Remote 'copy' { scp -q -o BatchMode=yes "${Server}:learninge-backup/*" $target }
    $sums = @(Get-Content (Join-Path $target 'SHA256SUMS'))
    if ($sums.Count -ne 2) { throw 'expected 2 files' }
    foreach ($line in $sums) {
        $hash, $name = $line -split '\s+', 2
        $file = Join-Path $target ($name -replace '^\*?\./', '')
        if ((Get-FileHash $file -Algorithm SHA256).Hash -ne $hash) { throw "checksum mismatch: $name" }
    }
    Invoke-Remote 'cleanup' { ssh -o BatchMode=yes $Server 'rm -rf ~/learninge-backup' }
} catch {
    Remove-Item -Recurse -Force $target
    throw
}
Get-ChildItem $Destination -Directory | Where-Object { $_.Name -match '^\d{8}-\d{6}$' } |
    Sort-Object Name -Descending | Select-Object -Skip $Keep | Remove-Item -Recurse -Force
Write-Output "Saved to $target"
