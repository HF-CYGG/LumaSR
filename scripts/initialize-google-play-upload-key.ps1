[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$signingDirectory = Join-Path $projectRoot 'artifacts\signing'
$keystorePath = Join-Path $signingDirectory 'lumasr-play-upload.jks'
$environmentPath = Join-Path $signingDirectory 'lumasr-play-upload-signing.env'
$certificatePath = Join-Path $signingDirectory 'lumasr-play-upload-cert.pem'
$keyAlias = 'lumasr-play-upload'
$managedPaths = @($keystorePath, $environmentPath, $certificatePath)

foreach ($path in $managedPaths) {
    if (Test-Path -LiteralPath $path) {
        throw "Refusing to overwrite existing Google Play signing material: $path"
    }
}

New-Item -ItemType Directory -Force -Path $signingDirectory | Out-Null

function New-RandomPassword {
    $bytes = New-Object byte[] 48
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    return [Convert]::ToBase64String($bytes)
}

$storePassword = New-RandomPassword
$keyPassword = New-RandomPassword
$temporaryDerPath = Join-Path $signingDirectory ('.play-upload-' + [guid]::NewGuid().ToString('N') + '.der')
$completed = $false

try {
    $env:LUMASR_PLAY_UPLOAD_INIT_STORE_PASSWORD = $storePassword
    $env:LUMASR_PLAY_UPLOAD_INIT_KEY_PASSWORD = $keyPassword

    & keytool '-J-Duser.language=en' '-J-Duser.country=US' `
        -genkeypair `
        -noprompt `
        -keystore $keystorePath `
        -storetype JKS `
        '-storepass:env' LUMASR_PLAY_UPLOAD_INIT_STORE_PASSWORD `
        '-keypass:env' LUMASR_PLAY_UPLOAD_INIT_KEY_PASSWORD `
        -alias $keyAlias `
        -keyalg RSA `
        -keysize 4096 `
        -sigalg SHA256withRSA `
        -validity 10000 `
        -dname 'CN=LumaSR Play Upload, OU=Release, O=LumaSR'
    if ($LASTEXITCODE -ne 0) {
        throw 'keytool failed to generate the dedicated Google Play upload key.'
    }

    & keytool '-J-Duser.language=en' '-J-Duser.country=US' `
        -exportcert `
        -keystore $keystorePath `
        '-storepass:env' LUMASR_PLAY_UPLOAD_INIT_STORE_PASSWORD `
        -alias $keyAlias `
        -file $temporaryDerPath
    if ($LASTEXITCODE -ne 0) {
        throw 'keytool failed to export the Google Play upload certificate.'
    }

    & keytool '-J-Duser.language=en' '-J-Duser.country=US' `
        -exportcert `
        -rfc `
        -keystore $keystorePath `
        '-storepass:env' LUMASR_PLAY_UPLOAD_INIT_STORE_PASSWORD `
        -alias $keyAlias `
        -file $certificatePath
    if ($LASTEXITCODE -ne 0) {
        throw 'keytool failed to export the PEM Google Play upload certificate.'
    }

    $certificateSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporaryDerPath).Hash.ToUpperInvariant()
    @(
        "LUMASR_PLAY_UPLOAD_STORE_FILE=$keystorePath"
        "LUMASR_PLAY_UPLOAD_STORE_PASSWORD=$storePassword"
        "LUMASR_PLAY_UPLOAD_KEY_ALIAS=$keyAlias"
        "LUMASR_PLAY_UPLOAD_KEY_PASSWORD=$keyPassword"
        "LUMASR_PLAY_UPLOAD_CERT_SHA256=$certificateSha256"
    ) | Set-Content -Encoding ASCII -LiteralPath $environmentPath

    $completed = $true
    Write-Host "Google Play upload keystore created: $keystorePath"
    Write-Host "Google Play upload certificate created: $certificatePath"
    Write-Host "Google Play upload certificate SHA-256: $certificateSha256"
    Write-Host 'Back up the keystore and environment file securely before uploading a release.'
} finally {
    Remove-Item Env:LUMASR_PLAY_UPLOAD_INIT_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:LUMASR_PLAY_UPLOAD_INIT_KEY_PASSWORD -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $temporaryDerPath) {
        Remove-Item -LiteralPath $temporaryDerPath -Force
    }
    if (!$completed) {
        foreach ($path in $managedPaths) {
            if (Test-Path -LiteralPath $path) {
                Remove-Item -LiteralPath $path -Force
            }
        }
    }
}
