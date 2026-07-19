[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$TagName,

    [switch]$Publish
)

$ErrorActionPreference = 'Stop'
$repository = 'HF-CYGG/LumaSR'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifactDirectory = Join-Path $projectRoot "artifacts\releases\$TagName"
$apkPath = Join-Path $artifactDirectory 'LumaSR.apk'
$checksumPath = Join-Path $artifactDirectory 'LumaSR.apk.sha256'
$manifestPath = Join-Path $artifactDirectory 'release-manifest.json'
$notesPath = Join-Path $projectRoot "docs\releases\$TagName.md"
$metadataValidatorPath = Join-Path $PSScriptRoot 'validate-release-metadata.ps1'
$releaseVerifierPath = Join-Path $PSScriptRoot 'verify-release-contract.ps1'

& $metadataValidatorPath -TagName $TagName -ProjectRoot $projectRoot

foreach ($requiredPath in @($apkPath, $checksumPath, $manifestPath, $notesPath)) {
    if (!(Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Release input is missing: $requiredPath"
    }
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
if (
    $manifest.schemaVersion -ne 1 -or
    $manifest.repository -cne $repository -or
    $manifest.tagName -cne $TagName -or
    $manifest.assetName -cne 'LumaSR.apk'
) {
    throw 'Release manifest does not match the immutable GitHub Release contract.'
}

& $releaseVerifierPath `
    -ApkPath $apkPath `
    -ExpectedTag $TagName `
    -ExpectedVersionName $manifest.versionName `
    -ExpectedVersionCode ([long]$manifest.versionCode)

$actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath).Hash.ToLowerInvariant()
$checksumMatch = [regex]::Match(
    (Get-Content -Raw -LiteralPath $checksumPath).Trim(),
    '^([0-9a-f]{64})  LumaSR\.apk$'
)
if (!$checksumMatch.Success -or $checksumMatch.Groups[1].Value -cne $actualSha256) {
    throw 'LumaSR.apk.sha256 does not match the canonical Release APK.'
}
if ($manifest.sha256 -cne $actualSha256) {
    throw 'release-manifest.json does not match the canonical Release APK.'
}

Push-Location $projectRoot
try {
    $branch = (& git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or $branch -cne 'main') {
        throw "GitHub Release publication must run from main, current branch: $branch"
    }
    $workingTreeState = (& git status --porcelain=v1 --untracked-files=normal) -join "`n"
    if ($LASTEXITCODE -ne 0 -or ![string]::IsNullOrWhiteSpace($workingTreeState)) {
        throw 'GitHub Release publication requires a clean working tree.'
    }
    & git show-ref --verify --quiet "refs/tags/$TagName"
    if ($LASTEXITCODE -ne 0) {
        throw "Local Release tag is missing: $TagName"
    }
    $headCommit = (& git rev-parse HEAD).Trim()
    $tagCommit = (& git rev-list -n 1 $TagName).Trim()
    if ($LASTEXITCODE -ne 0 -or $tagCommit -cne $headCommit) {
        throw "Release tag $TagName must point to current main HEAD."
    }
} finally {
    Pop-Location
}

if (!$Publish) {
    Write-Host "GitHub Release preflight passed for $TagName. Re-run with -Publish to publish."
    exit 0
}

$null = Get-Command gh -ErrorAction Stop
& gh auth status --hostname github.com
if ($LASTEXITCODE -ne 0) {
    throw 'GitHub CLI is not authenticated for github.com.'
}

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
& gh release view $TagName --repo $repository *> $null
$releaseAlreadyExists = $LASTEXITCODE -eq 0
$ErrorActionPreference = $previousErrorActionPreference
if ($releaseAlreadyExists) {
    throw "GitHub Release already exists and will not be overwritten: $TagName"
}

& gh release create `
    $TagName `
    $apkPath `
    $checksumPath `
    $manifestPath `
    --repo $repository `
    --verify-tag `
    --title "LumaSR $TagName" `
    --notes-file $notesPath `
    --latest
if ($LASTEXITCODE -ne 0) {
    throw "GitHub Release publication failed: $TagName"
}

Write-Host "GitHub Release published: https://github.com/$repository/releases/tag/$TagName"
