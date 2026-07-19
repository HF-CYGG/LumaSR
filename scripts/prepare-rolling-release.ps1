[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ReleaseVersion,

    [string]$GradleUserHome
)

$ErrorActionPreference = 'Stop'
$repository = 'HF-CYGG/LumaSR'
$rollingTag = 'release'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixedBuildPath = Join-Path $PSScriptRoot 'build-fixed-release.ps1'
$metadataValidatorPath = Join-Path $PSScriptRoot 'validate-release-metadata.ps1'
$releaseVerifierPath = Join-Path $PSScriptRoot 'verify-release-contract.ps1'

if ($ReleaseVersion -cnotmatch '^[0-9]+(?:\.[0-9]+){1,3}$') {
    throw "ReleaseVersion must contain 2-4 numeric segments: $ReleaseVersion"
}

$versionTag = "v$ReleaseVersion"
& $metadataValidatorPath -TagName $versionTag -ProjectRoot $projectRoot

$fixedBuildArguments = @{}
if (![string]::IsNullOrWhiteSpace($GradleUserHome)) {
    $fixedBuildArguments['GradleUserHome'] = $GradleUserHome
}
& $fixedBuildPath @fixedBuildArguments

$fixedArtifactDirectory = Join-Path $projectRoot "artifacts\releases\$versionTag"
$fixedApkPath = Join-Path $fixedArtifactDirectory 'LumaSR.apk'
$fixedManifestPath = Join-Path $fixedArtifactDirectory 'release-manifest.json'
foreach ($requiredPath in @($fixedApkPath, $fixedManifestPath)) {
    if (!(Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Fixed-signing Release output is missing: $requiredPath"
    }
}

$fixedManifest = Get-Content -Raw -LiteralPath $fixedManifestPath | ConvertFrom-Json
if (
    $fixedManifest.schemaVersion -ne 1 -or
    $fixedManifest.repository -cne $repository -or
    $fixedManifest.tagName -cne $versionTag -or
    $fixedManifest.versionName -cne $ReleaseVersion -or
    $fixedManifest.assetName -cne 'LumaSR.apk'
) {
    throw 'Fixed-signing Release manifest does not match the requested rolling version.'
}

$assetName = "LumaSR-v$ReleaseVersion.apk"
$rollingArtifactDirectory = Join-Path $projectRoot "artifacts\releases\$rollingTag"
New-Item -ItemType Directory -Force -Path $rollingArtifactDirectory | Out-Null

$generatedFilePattern = '^LumaSR-v[0-9]+(?:\.[0-9]+){1,3}\.apk(?:\.sha256)?$'
Get-ChildItem -LiteralPath $rollingArtifactDirectory -File | Where-Object {
    $_.Name -match $generatedFilePattern -or $_.Name -ceq 'release-manifest.json'
} | ForEach-Object {
    Remove-Item -LiteralPath $_.FullName -Force
    Write-Host "Removed stale generated rolling artifact: $($_.Name)"
}

$rollingApkPath = Join-Path $rollingArtifactDirectory $assetName
Copy-Item -LiteralPath $fixedApkPath -Destination $rollingApkPath

& $releaseVerifierPath `
    -ApkPath $rollingApkPath `
    -ExpectedTag $versionTag `
    -ExpectedVersionName $fixedManifest.versionName `
    -ExpectedVersionCode ([long]$fixedManifest.versionCode) `
    -ExpectedAssetName $assetName

$sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $rollingApkPath).Hash.ToLowerInvariant()
$checksumPath = Join-Path $rollingArtifactDirectory "$assetName.sha256"
"$sha256  $assetName" | Set-Content -Encoding ASCII -LiteralPath $checksumPath

$rollingManifestPath = Join-Path $rollingArtifactDirectory 'release-manifest.json'
[ordered]@{
    schemaVersion = 1
    repository = $repository
    tagName = 'release'
    releaseTitle = "LumaSR v$ReleaseVersion"
    versionName = $fixedManifest.versionName
    versionCode = [long]$fixedManifest.versionCode
    assetName = $assetName
    sha256 = $sha256
    certificateSha256 = $fixedManifest.certificateSha256
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $rollingManifestPath

Write-Host "Rolling Release APK: $rollingApkPath"
Write-Host "Rolling Release checksum: $checksumPath"
Write-Host "Rolling Release manifest: $rollingManifestPath"
