[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$TagName,

    [string]$ProjectRoot
)

$ErrorActionPreference = 'Stop'
if ($TagName -cne 'release') {
    throw "Rolling Release tag must be exactly release: $TagName"
}
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Join-Path $PSScriptRoot '..'
}

$resolvedProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$buildScriptPath = Join-Path $resolvedProjectRoot 'app\build.gradle.kts'
$buildScript = Get-Content -Raw -Encoding UTF8 -LiteralPath $buildScriptPath
$versionNameMatches = [regex]::Matches(
    $buildScript,
    'versionName\s*=\s*"([0-9]+(?:\.[0-9]+){1,3})"'
)
if ($versionNameMatches.Count -ne 1) {
    throw 'app/build.gradle.kts must declare one numeric versionName for rolling release.'
}

$versionName = $versionNameMatches[0].Groups[1].Value
$metadataValidatorPath = Join-Path $PSScriptRoot 'validate-release-metadata.ps1'
& $metadataValidatorPath -TagName "v$versionName" -ProjectRoot $resolvedProjectRoot

Write-Host "Rolling Release metadata verified: tag=$TagName title=LumaSR v$versionName"
