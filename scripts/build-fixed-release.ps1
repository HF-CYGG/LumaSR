[CmdletBinding()]
param(
    [string]$GradleUserHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$signingEnvironmentPath = Join-Path $projectRoot 'artifacts\signing\lumasr-selfdist-signing.env'
$signingVerifierPath = Join-Path $PSScriptRoot 'verify-fixed-release-signing.ps1'
$releaseVerifierPath = Join-Path $PSScriptRoot 'verify-release-contract.ps1'
$metadataValidatorPath = Join-Path $PSScriptRoot 'validate-release-metadata.ps1'
$signingVariableNames = @(
    'LUMASR_UPLOAD_STORE_FILE',
    'LUMASR_UPLOAD_STORE_PASSWORD',
    'LUMASR_UPLOAD_KEY_ALIAS',
    'LUMASR_UPLOAD_KEY_PASSWORD'
)

if (!(Test-Path -LiteralPath $signingEnvironmentPath -PathType Leaf)) {
    throw "Fixed signing environment file is missing: $signingEnvironmentPath"
}

$signingValues = @{}
Get-Content -LiteralPath $signingEnvironmentPath | ForEach-Object {
    $line = $_.Trim()
    if ($line.Length -gt 0 -and !$line.StartsWith('#')) {
        $parts = $line -split '=', 2
        if ($parts.Count -ne 2 -or $signingVariableNames -notcontains $parts[0]) {
            throw "Unsupported entry in fixed signing environment file: $($parts[0])"
        }
        $signingValues[$parts[0]] = $parts[1]
    }
}

foreach ($variableName in $signingVariableNames) {
    if ([string]::IsNullOrWhiteSpace($signingValues[$variableName])) {
        throw "Fixed signing value is missing: $variableName"
    }
}

$previousEnvironment = @{}
foreach ($variableName in $signingVariableNames) {
    $previousEnvironment[$variableName] = [Environment]::GetEnvironmentVariable($variableName, 'Process')
    [Environment]::SetEnvironmentVariable($variableName, $signingValues[$variableName], 'Process')
}

try {
    & $signingVerifierPath `
        -KeystorePath $env:LUMASR_UPLOAD_STORE_FILE `
        -KeyAlias $env:LUMASR_UPLOAD_KEY_ALIAS
    if ($LASTEXITCODE -ne 0) {
        throw 'Fixed signing keystore verification failed.'
    }

    Push-Location $projectRoot
    try {
        $gradleArguments = @()
        if (![string]::IsNullOrWhiteSpace($GradleUserHome)) {
            $resolvedGradleUserHome = [System.IO.Path]::GetFullPath($GradleUserHome)
            New-Item -ItemType Directory -Force -Path $resolvedGradleUserHome | Out-Null
            $gradleArguments += @('-g', $resolvedGradleUserHome)
        }
        $gradleArguments += @(
            'testDebugUnitTest',
            'lintDebug',
            'assembleRelease',
            '--console=plain'
        )
        & .\gradlew.bat @gradleArguments
        if ($LASTEXITCODE -ne 0) {
            throw 'Fixed-signing Release build failed.'
        }
    } finally {
        Pop-Location
    }

    $metadataPath = Join-Path $projectRoot 'app\build\outputs\apk\release\output-metadata.json'
    $metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
    $releaseElement = $metadata.elements | Select-Object -First 1
    if ($null -eq $releaseElement) {
        throw 'Release output metadata does not contain an APK element.'
    }
    $releaseTag = "v$($releaseElement.versionName)"
    & $metadataValidatorPath -TagName $releaseTag -ProjectRoot $projectRoot
    if ($LASTEXITCODE -ne 0) {
        throw 'Release metadata verification failed.'
    }

    $builtApkPath = Join-Path (Split-Path -Parent $metadataPath) $releaseElement.outputFile
    & $signingVerifierPath -ApkPath $builtApkPath
    if ($LASTEXITCODE -ne 0) {
        throw 'Built Release APK certificate verification failed.'
    }

    $artifactDirectory = Join-Path $projectRoot "artifacts\releases\$releaseTag"
    New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
    $artifactPath = Join-Path $artifactDirectory 'LumaSR.apk'
    Copy-Item -LiteralPath $builtApkPath -Destination $artifactPath -Force

    & $releaseVerifierPath `
        -ApkPath $artifactPath `
        -ExpectedTag $releaseTag `
        -ExpectedVersionName $releaseElement.versionName `
        -ExpectedVersionCode ([long]$releaseElement.versionCode) `
        -ExpectedAssetName 'LumaSR.apk'
    if ($LASTEXITCODE -ne 0) {
        throw 'Published Release APK contract verification failed.'
    }

    $sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $artifactPath).Hash.ToLowerInvariant()
    $checksumPath = Join-Path $artifactDirectory 'LumaSR.apk.sha256'
    "$sha256  LumaSR.apk" | Set-Content -Encoding ASCII -LiteralPath $checksumPath

    $releaseManifestPath = Join-Path $artifactDirectory 'release-manifest.json'
    [ordered]@{
        schemaVersion = 1
        repository = 'HF-CYGG/LumaSR'
        tagName = $releaseTag
        versionName = $releaseElement.versionName
        versionCode = [long]$releaseElement.versionCode
        assetName = 'LumaSR.apk'
        sha256 = $sha256
        certificateSha256 = '43648398DD74C72B1CBA185D3832E558C72D9BB58AE46BB9C09EF32818988D2F'
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    } | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $releaseManifestPath

    Write-Host "Fixed-signing Release APK: $artifactPath"
    Write-Host "Release checksum: $checksumPath"
    Write-Host "Release manifest: $releaseManifestPath"
} finally {
    foreach ($variableName in $signingVariableNames) {
        [Environment]::SetEnvironmentVariable(
            $variableName,
            $previousEnvironment[$variableName],
            'Process'
        )
    }
}
