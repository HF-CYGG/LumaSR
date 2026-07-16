[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$signingEnvironmentPath = Join-Path $projectRoot 'artifacts\signing\lumasr-selfdist-signing.env'
$signingVerifierPath = Join-Path $PSScriptRoot 'verify-fixed-release-signing.ps1'
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
        & .\gradlew.bat testDebugUnitTest lintDebug assembleRelease --console=plain
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

    $builtApkPath = Join-Path (Split-Path -Parent $metadataPath) $releaseElement.outputFile
    & $signingVerifierPath -ApkPath $builtApkPath
    if ($LASTEXITCODE -ne 0) {
        throw 'Built Release APK certificate verification failed.'
    }

    $artifactDirectory = Join-Path $projectRoot 'artifacts\releases'
    New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
    $artifactPath = Join-Path $artifactDirectory (
        "LumaSR-$($releaseElement.versionName)-release-arm64-v8a.apk"
    )
    Copy-Item -LiteralPath $builtApkPath -Destination $artifactPath -Force

    & $signingVerifierPath -ApkPath $artifactPath
    if ($LASTEXITCODE -ne 0) {
        throw 'Published Release APK certificate verification failed.'
    }

    Write-Host "Fixed-signing Release APK: $artifactPath"
} finally {
    foreach ($variableName in $signingVariableNames) {
        [Environment]::SetEnvironmentVariable(
            $variableName,
            $previousEnvironment[$variableName],
            'Process'
        )
    }
}
