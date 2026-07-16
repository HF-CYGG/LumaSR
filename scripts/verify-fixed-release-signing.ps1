[CmdletBinding(DefaultParameterSetName = 'Apk')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Apk')]
    [string]$ApkPath,

    [Parameter(Mandatory, ParameterSetName = 'Keystore')]
    [string]$KeystorePath,

    [Parameter(Mandatory, ParameterSetName = 'Keystore')]
    [string]$KeyAlias,

    [Parameter(ParameterSetName = 'Keystore')]
    [string]$StorePasswordEnvironmentVariable = 'LUMASR_UPLOAD_STORE_PASSWORD'
)

$ErrorActionPreference = 'Stop'
$expectedCertificateSha256 = '43648398DD74C72B1CBA185D3832E558C72D9BB58AE46BB9C09EF32818988D2F'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Normalize-CertificateDigest {
    param([string]$Digest)

    return ($Digest -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
}

function Find-ApkSigner {
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
    $apkSigner = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($apkSigner)) {
        throw "apksigner.bat was not found under $buildToolsRoot"
    }
    return $apkSigner
}

if ($PSCmdlet.ParameterSetName -eq 'Apk') {
    $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
    $apkSigner = Find-ApkSigner
    $verificationOutput = & $apkSigner verify --print-certs $resolvedApk 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner rejected APK: $resolvedApk"
    }
    $match = [regex]::Match(
        ($verificationOutput -join "`n"),
        'certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)'
    )
} else {
    $resolvedKeystore = (Resolve-Path -LiteralPath $KeystorePath).Path
    if ([string]::IsNullOrWhiteSpace(
        [Environment]::GetEnvironmentVariable($StorePasswordEnvironmentVariable, 'Process')
    )) {
        throw "Signing password environment variable is missing: $StorePasswordEnvironmentVariable"
    }
    $verificationOutput = & keytool -list -v `
        -keystore $resolvedKeystore `
        '-storepass:env' $StorePasswordEnvironmentVariable `
        -alias $KeyAlias 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "keytool rejected keystore: $resolvedKeystore"
    }
    $match = [regex]::Match(($verificationOutput -join "`n"), 'SHA256:\s*([0-9A-Fa-f:]+)')
}

if (!$match.Success) {
    throw 'Certificate SHA-256 digest was not present in verifier output.'
}

$actualCertificateSha256 = Normalize-CertificateDigest $match.Groups[1].Value
if ($actualCertificateSha256 -cne $expectedCertificateSha256) {
    throw "Release signing certificate mismatch. Expected $expectedCertificateSha256 but found $actualCertificateSha256"
}

Write-Host "Fixed release certificate verified: $actualCertificateSha256"
