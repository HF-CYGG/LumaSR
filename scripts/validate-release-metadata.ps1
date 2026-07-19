[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$TagName,

    [string]$ProjectRoot
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Join-Path $PSScriptRoot '..'
}
$resolvedProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$buildScriptPath = Join-Path $resolvedProjectRoot 'app\build.gradle.kts'
$buildScript = Get-Content -Raw -LiteralPath $buildScriptPath

$tagMatch = [regex]::Match($TagName, '^v([0-9]+(?:\.[0-9]+){1,3})$')
if (!$tagMatch.Success) {
    throw "Release tag must use v followed by 2-4 numeric segments: $TagName"
}

$versionNameMatches = [regex]::Matches(
    $buildScript,
    'versionName\s*=\s*"([^"]+)"'
)
$versionCodeMatches = [regex]::Matches(
    $buildScript,
    'versionCode\s*=\s*([0-9]+)'
)
if ($versionNameMatches.Count -ne 1 -or $versionCodeMatches.Count -ne 1) {
    throw 'app/build.gradle.kts must declare one literal versionName and versionCode.'
}

$versionName = $versionNameMatches[0].Groups[1].Value
$versionCode = [long]$versionCodeMatches[0].Groups[1].Value
if ($tagMatch.Groups[1].Value -cne $versionName) {
    throw "Release tag $TagName does not match app version $versionName."
}
if ($versionCode -le 0) {
    throw "Release versionCode must be positive: $versionCode"
}

$releaseNotesPath = Join-Path $resolvedProjectRoot "docs\releases\$TagName.md"
if (!(Test-Path -LiteralPath $releaseNotesPath -PathType Leaf)) {
    throw "Release notes are missing: $releaseNotesPath"
}
if ([string]::IsNullOrWhiteSpace((Get-Content -Raw -LiteralPath $releaseNotesPath))) {
    throw "Release notes cannot be empty: $releaseNotesPath"
}

Write-Host "Release metadata verified: tag=$TagName versionName=$versionName versionCode=$versionCode"
