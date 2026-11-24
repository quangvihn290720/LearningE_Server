# Authoring tool for content-data. Examples:
#   tools/content.ps1 check | lock
#   tools/content.ps1 export T05 T05.csv      tools/content.ps1 import T05 T05.csv      tools/content.ps1 new T16 T16.csv
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$root = Split-Path $PSScriptRoot
$list = @($args)
# CSV paths are relative to the caller, Gradle runs in the repository root.
if ($list.Count -eq 3) { $list[2] = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($list[2]) }
$quoted = ($list | ForEach-Object { "'$_'" }) -join ' '
& "$root/gradlew.bat" -p $root -q --console=plain :content:contentTool --args $quoted
exit $LASTEXITCODE
