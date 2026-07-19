[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ApkPath,

    [Parameter(Mandatory)]
    [string]$ExpectedTag,

    [Parameter(Mandatory)]
    [string]$ExpectedVersionName,

    [Parameter(Mandatory)]
    [long]$ExpectedVersionCode,

    [Parameter(Mandatory)]
    [string]$ExpectedAssetName
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$metadataValidatorPath = Join-Path $PSScriptRoot 'validate-release-metadata.ps1'
$signingVerifierPath = Join-Path $PSScriptRoot 'verify-fixed-release-signing.ps1'

function Find-AndroidBuildTool {
    param([Parameter(Mandatory)][string]$ToolName)

    $sdkRoot = $env:ANDROID_SDK_ROOT
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        $sdkRoot = $env:ANDROID_HOME
    }
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        $localProperties = Join-Path $projectRoot 'local.properties'
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_.StartsWith('sdk.dir=') } |
            Select-Object -First 1
        if ($null -eq $sdkLine) {
            throw 'Android SDK path is not configured.'
        }
        $sdkRoot = $sdkLine.Substring('sdk.dir='.Length).Replace('\:', ':').Replace('\\', '\')
    }

    $buildToolsRoot = Join-Path $sdkRoot 'build-tools'
    $toolPath = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName "$ToolName.bat" } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($toolPath)) {
        $toolPath = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName "$ToolName.exe" } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Select-Object -First 1
    }
    if ([string]::IsNullOrWhiteSpace($toolPath)) {
        throw "$ToolName was not found under $buildToolsRoot"
    }
    return $toolPath
}

& $metadataValidatorPath -TagName $ExpectedTag -ProjectRoot $projectRoot

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
if ((Split-Path -Leaf $resolvedApk) -cne $ExpectedAssetName) {
    throw "GitHub Release APK must be named $ExpectedAssetName`: $resolvedApk"
}
if ((Get-Item -LiteralPath $resolvedApk).Length -le 0) {
    throw "Release APK is empty: $resolvedApk"
}

& $signingVerifierPath -ApkPath $resolvedApk
if ($LASTEXITCODE -ne 0) {
    throw 'Release APK certificate verification failed.'
}

$aapt2 = Find-AndroidBuildTool -ToolName 'aapt2'
$badgingOutput = & $aapt2 dump badging $resolvedApk 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "aapt2 rejected Release APK: $resolvedApk"
}
$packageMatch = [regex]::Match(
    ($badgingOutput -join "`n"),
    "package: name='com\.lumasr' versionCode='([0-9]+)' versionName='([^']+)'"
)
if (!$packageMatch.Success) {
    throw 'Release APK package version metadata was not found.'
}
$actualVersionCode = [long]$packageMatch.Groups[1].Value
$actualVersionName = $packageMatch.Groups[2].Value
if ($actualVersionCode -ne $ExpectedVersionCode -or $actualVersionName -cne $ExpectedVersionName) {
    throw (
        "Release APK version mismatch. Expected $ExpectedVersionName ($ExpectedVersionCode), " +
        "found $actualVersionName ($actualVersionCode)."
    )
}

$zipalign = Find-AndroidBuildTool -ToolName 'zipalign'
& $zipalign -c -P 16 4 $resolvedApk
if ($LASTEXITCODE -ne 0) {
    throw "zipalign rejected Release APK: $resolvedApk"
}

Write-Host (
    "Release APK contract verified: tag=$ExpectedTag " +
    "versionName=$actualVersionName versionCode=$actualVersionCode asset=$ExpectedAssetName"
)
