[CmdletBinding()]
param(
    [string]$GradleUserHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$selfDistEnvironmentPath = Join-Path $projectRoot 'artifacts\signing\lumasr-selfdist-signing.env'
$playEnvironmentPath = Join-Path $projectRoot 'artifacts\signing\lumasr-play-upload-signing.env'
$playCertificatePath = Join-Path $projectRoot 'artifacts\signing\lumasr-play-upload-cert.pem'
$fixedSigningVerifierPath = Join-Path $PSScriptRoot 'verify-fixed-release-signing.ps1'
$expectedPackageName = 'com.lumasr'
$expectedVersionName = '0.3.4'
$expectedVersionCode = 5L
$expectedTargetSdk = 36
$fixedAppSigningCertificateSha256 = '43648398DD74C72B1CBA185D3832E558C72D9BB58AE46BB9C09EF32818988D2F'
$selfDistVariableNames = @(
    'LUMASR_UPLOAD_STORE_FILE',
    'LUMASR_UPLOAD_STORE_PASSWORD',
    'LUMASR_UPLOAD_KEY_ALIAS',
    'LUMASR_UPLOAD_KEY_PASSWORD'
)
$playVariableNames = @(
    'LUMASR_PLAY_UPLOAD_STORE_FILE',
    'LUMASR_PLAY_UPLOAD_STORE_PASSWORD',
    'LUMASR_PLAY_UPLOAD_KEY_ALIAS',
    'LUMASR_PLAY_UPLOAD_KEY_PASSWORD',
    'LUMASR_PLAY_UPLOAD_CERT_SHA256'
)

function Read-SigningEnvironment {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string[]]$AllowedNames
    )

    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Signing environment file is missing: $Path"
    }
    $values = @{}
    Get-Content -LiteralPath $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line.Length -gt 0 -and !$line.StartsWith('#')) {
            $parts = $line -split '=', 2
            if ($parts.Count -ne 2 -or $AllowedNames -notcontains $parts[0]) {
                throw "Unsupported entry in signing environment file: $($parts[0])"
            }
            $values[$parts[0]] = $parts[1]
        }
    }
    foreach ($name in $AllowedNames) {
        if ([string]::IsNullOrWhiteSpace($values[$name])) {
            throw "Signing value is missing: $name"
        }
    }
    return $values
}

function Normalize-CertificateDigest {
    param([Parameter(Mandatory)] [string]$Digest)
    return ($Digest -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
}

function Get-SdkRoot {
    if (![string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        return $env:ANDROID_SDK_ROOT
    }
    if (![string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        return $env:ANDROID_HOME
    }
    $localProperties = Join-Path $projectRoot 'local.properties'
    $sdkLine = Get-Content -LiteralPath $localProperties |
        Where-Object { $_.StartsWith('sdk.dir=') } |
        Select-Object -First 1
    if ($null -eq $sdkLine) {
        throw 'Android SDK path is not configured.'
    }
    return $sdkLine.Substring('sdk.dir='.Length).Replace('\:', ':').Replace('\\', '\')
}

function Find-BuildTool {
    param([Parameter(Mandatory)] [string]$Name)
    $buildToolsRoot = Join-Path (Get-SdkRoot) 'build-tools'
    $tool = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName $Name } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($tool)) {
        throw "$Name was not found under $buildToolsRoot"
    }
    return $tool
}

function Export-CertificateDer {
    param(
        [Parameter(Mandatory)] [string]$KeystorePath,
        [Parameter(Mandatory)] [string]$KeyAlias,
        [Parameter(Mandatory)] [string]$PasswordVariable,
        [Parameter(Mandatory)] [string]$OutputPath
    )
    & keytool '-J-Duser.language=en' '-J-Duser.country=US' `
        -exportcert `
        -keystore $KeystorePath `
        '-storepass:env' $PasswordVariable `
        -alias $KeyAlias `
        -file $OutputPath
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed to export certificate for alias $KeyAlias"
    }
}

function Get-JarCertificateSha256 {
    param([Parameter(Mandatory)] [string]$JarPath)
    $certificateOutput = & keytool '-J-Duser.language=en' '-J-Duser.country=US' `
        -printcert -jarfile $JarPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "keytool rejected signed archive: $JarPath"
    }
    $match = [regex]::Match(($certificateOutput -join "`n"), 'SHA256:\s*([0-9A-Fa-f:]+)')
    if (!$match.Success) {
        throw "SHA-256 signer fingerprint was not found in $JarPath"
    }
    return Normalize-CertificateDigest $match.Groups[1].Value
}

$selfDistValues = Read-SigningEnvironment `
    -Path $selfDistEnvironmentPath `
    -AllowedNames $selfDistVariableNames
$playValues = Read-SigningEnvironment `
    -Path $playEnvironmentPath `
    -AllowedNames $playVariableNames
$allVariableNames = $selfDistVariableNames + $playVariableNames
$previousEnvironment = @{}

foreach ($name in $allVariableNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    $source = if ($selfDistValues.ContainsKey($name)) { $selfDistValues } else { $playValues }
    [Environment]::SetEnvironmentVariable($name, $source[$name], 'Process')
}

$verificationRoot = Join-Path $projectRoot 'app\build\intermediates\google-play-verification'
$universalExtractionRoot = Join-Path $verificationRoot 'universal'
$universalApkExtractionRoot = Join-Path $verificationRoot 'universal-apk'
$selfDistCertificateDer = Join-Path $verificationRoot 'selfdist-cert.der'
$playCertificateDer = Join-Path $verificationRoot 'play-upload-cert.der'

try {
    New-Item -ItemType Directory -Force -Path $verificationRoot | Out-Null

    & $fixedSigningVerifierPath `
        -KeystorePath $env:LUMASR_UPLOAD_STORE_FILE `
        -KeyAlias $env:LUMASR_UPLOAD_KEY_ALIAS
    if ($LASTEXITCODE -ne 0) {
        throw 'Fixed app signing keystore verification failed.'
    }

    Export-CertificateDer `
        -KeystorePath $env:LUMASR_UPLOAD_STORE_FILE `
        -KeyAlias $env:LUMASR_UPLOAD_KEY_ALIAS `
        -PasswordVariable 'LUMASR_UPLOAD_STORE_PASSWORD' `
        -OutputPath $selfDistCertificateDer
    Export-CertificateDer `
        -KeystorePath $env:LUMASR_PLAY_UPLOAD_STORE_FILE `
        -KeyAlias $env:LUMASR_PLAY_UPLOAD_KEY_ALIAS `
        -PasswordVariable 'LUMASR_PLAY_UPLOAD_STORE_PASSWORD' `
        -OutputPath $playCertificateDer

    $actualAppCertificateSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $selfDistCertificateDer).Hash.ToUpperInvariant()
    $actualPlayCertificateSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $playCertificateDer).Hash.ToUpperInvariant()
    $expectedPlayCertificateSha256 = Normalize-CertificateDigest $env:LUMASR_PLAY_UPLOAD_CERT_SHA256
    if ($actualAppCertificateSha256 -cne $fixedAppSigningCertificateSha256) {
        throw 'The configured app signing certificate is not the fixed LumaSR certificate.'
    }
    if ($actualPlayCertificateSha256 -cne $expectedPlayCertificateSha256) {
        throw 'The configured Play upload certificate fingerprint does not match its environment file.'
    }
    if ($actualPlayCertificateSha256 -ceq $actualAppCertificateSha256) {
        throw 'The Play upload key must be distinct from the app signing key.'
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
            'assembleDebug',
            'assembleRelease',
            'assemblePlay',
            'bundlePlay',
            'buildPlayUniversalApks',
            '--console=plain'
        )
        & .\gradlew.bat @gradleArguments
        if ($LASTEXITCODE -ne 0) {
            throw 'Google Play release build and validation tasks failed.'
        }

        $quietArguments = @()
        if (![string]::IsNullOrWhiteSpace($GradleUserHome)) {
            $quietArguments += @('-g', [System.IO.Path]::GetFullPath($GradleUserHome))
        }
        $bundleConfigOutput = & .\gradlew.bat @quietArguments -q dumpPlayBundleConfig 2>&1
        if ($LASTEXITCODE -ne 0 -or ($bundleConfigOutput -join "`n") -notmatch 'PAGE_ALIGNMENT_16K') {
            throw 'bundletool did not report PAGE_ALIGNMENT_16K for the Play bundle.'
        }
        $bundleManifestOutput = & .\gradlew.bat @quietArguments -q dumpPlayBundleManifest 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw 'bundletool failed to dump the Play bundle manifest.'
        }
        $downloadSizeOutput = & .\gradlew.bat @quietArguments -q getPlayDownloadSize 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw 'bundletool failed to estimate the Play download size.'
        }
    } finally {
        Pop-Location
    }

    $bundleManifestText = $bundleManifestOutput -join "`n"
    foreach ($requiredManifestPattern in @(
        'package="com\.lumasr"',
        'android:versionCode="5"',
        'android:versionName="0\.3\.4"',
        'android:targetSdkVersion="36"'
    )) {
        if ($bundleManifestText -notmatch $requiredManifestPattern) {
            throw "Play bundle manifest is missing expected value: $requiredManifestPattern"
        }
    }
    $downloadSizeMatch = [regex]::Match(
        ($downloadSizeOutput -join "`n"),
        '(?m)^\s*(\d+)\s*,\s*(\d+)\s*$'
    )
    if (!$downloadSizeMatch.Success) {
        throw 'bundletool download size output did not contain a numeric MIN,MAX row.'
    }
    $estimatedCompressedDownloadBytesMin = [long]$downloadSizeMatch.Groups[1].Value
    $estimatedCompressedDownloadBytesMax = [long]$downloadSizeMatch.Groups[2].Value

    $playBundlePath = Join-Path $projectRoot 'app\build\outputs\bundle\play\app-play.aab'
    $releaseApkPath = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
    $releaseMetadataPath = Join-Path $projectRoot 'app\build\outputs\apk\release\output-metadata.json'
    $playMappingPath = Join-Path $projectRoot 'app\build\outputs\mapping\play\mapping.txt'
    $playNativeSymbolsPath = Join-Path $projectRoot 'app\build\outputs\native-debug-symbols\play\native-debug-symbols.zip'
    $universalApksPath = Join-Path $projectRoot 'build\bundletool\play-universal.apks'
    foreach ($requiredPath in @(
        $playBundlePath,
        $releaseApkPath,
        $releaseMetadataPath,
        $playMappingPath,
        $playNativeSymbolsPath,
        $universalApksPath,
        $playCertificatePath
    )) {
        if (!(Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw "Required Google Play release output is missing: $requiredPath"
        }
    }

    $releaseMetadata = Get-Content -Raw -LiteralPath $releaseMetadataPath | ConvertFrom-Json
    $releaseElement = $releaseMetadata.elements | Select-Object -First 1
    if ($null -eq $releaseElement -or
        [long]$releaseElement.versionCode -ne $expectedVersionCode -or
        [string]$releaseElement.versionName -cne $expectedVersionName) {
        throw 'Release APK metadata does not match version 0.3.4 (5).'
    }

    & jarsigner -verify -verbose -certs $playBundlePath | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'jarsigner rejected the Google Play App Bundle.'
    }
    $bundleSignerSha256 = Get-JarCertificateSha256 -JarPath $playBundlePath
    if ($bundleSignerSha256 -cne $expectedPlayCertificateSha256) {
        throw 'The Google Play App Bundle is not signed by the dedicated upload key.'
    }

    & $fixedSigningVerifierPath -ApkPath $releaseApkPath
    if ($LASTEXITCODE -ne 0) {
        throw 'The installable Release APK failed fixed certificate verification.'
    }

    if (Test-Path -LiteralPath $universalExtractionRoot) {
        $resolvedExtractionRoot = [System.IO.Path]::GetFullPath($universalExtractionRoot)
        $expectedBuildPrefix = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'app\build\intermediates\'))
        if (!$resolvedExtractionRoot.StartsWith($expectedBuildPrefix)) {
            throw 'Refusing to clean an unexpected bundletool extraction directory.'
        }
        Remove-Item -LiteralPath $resolvedExtractionRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Path $universalExtractionRoot | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($universalApksPath, $universalExtractionRoot)
    $universalApkPath = Join-Path $universalExtractionRoot 'universal.apk'
    if (!(Test-Path -LiteralPath $universalApkPath -PathType Leaf)) {
        throw 'bundletool did not produce universal.apk.'
    }
    if (Test-Path -LiteralPath $universalApkExtractionRoot) {
        $resolvedApkExtractionRoot = [System.IO.Path]::GetFullPath($universalApkExtractionRoot)
        $expectedBuildPrefix = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'app\build\intermediates\'))
        if (!$resolvedApkExtractionRoot.StartsWith($expectedBuildPrefix)) {
            throw 'Refusing to clean an unexpected universal APK extraction directory.'
        }
        Remove-Item -LiteralPath $resolvedApkExtractionRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Path $universalApkExtractionRoot | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory($universalApkPath, $universalApkExtractionRoot)

    $aapt2 = Find-BuildTool 'aapt2.exe'
    $zipalign = Find-BuildTool 'zipalign.exe'
    $badging = & $aapt2 dump badging $universalApkPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'aapt2 rejected the universal APK generated from the Play bundle.'
    }
    $badgingText = $badging -join "`n"
    foreach ($requiredBadgingPattern in @(
        "package: name='$expectedPackageName' versionCode='$expectedVersionCode' versionName='$expectedVersionName'",
        "targetSdkVersion:'$expectedTargetSdk'"
    )) {
        if ($badgingText -notmatch [regex]::Escape($requiredBadgingPattern)) {
            throw "Universal APK badging is missing: $requiredBadgingPattern"
        }
    }

    & $zipalign -c -P 16 -v 4 $universalApkPath | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'The universal APK is not 16 KB ZIP aligned.'
    }

    $ndkVersionMatch = [regex]::Match(
        (Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'app\build.gradle.kts')),
        'ndkVersion\s*=\s*"([^"]+)"'
    )
    if (!$ndkVersionMatch.Success) {
        throw 'Unable to resolve ndkVersion from app/build.gradle.kts.'
    }
    $ndkRoot = Join-Path (Get-SdkRoot) ("ndk\" + $ndkVersionMatch.Groups[1].Value)
    $llvmObjdump = Get-ChildItem -LiteralPath $ndkRoot -Recurse -Filter 'llvm-objdump.exe' -File |
        Select-Object -First 1 -ExpandProperty FullName
    $llvmReadelf = Get-ChildItem -LiteralPath $ndkRoot -Recurse -Filter 'llvm-readelf.exe' -File |
        Select-Object -First 1 -ExpandProperty FullName
    if ([string]::IsNullOrWhiteSpace($llvmObjdump) -or [string]::IsNullOrWhiteSpace($llvmReadelf)) {
        throw "NDK ELF inspection tools were not found under $ndkRoot"
    }

    $nativeLibraryRoot = Join-Path $universalApkExtractionRoot 'lib'
    $nativeLibraries = Get-ChildItem -LiteralPath $nativeLibraryRoot -Recurse -Filter '*.so' -File
    if ($nativeLibraries.Count -eq 0) {
        throw 'No native libraries were present in the universal APK.'
    }
    foreach ($nativeLibrary in $nativeLibraries) {
        $loadOutput = & $llvmObjdump -p $nativeLibrary.FullName |
            Select-String -Pattern '^\s*LOAD'
        if ($loadOutput.Count -eq 0) {
            throw "No ELF LOAD segments found: $($nativeLibrary.Name)"
        }
        foreach ($loadLine in $loadOutput) {
            $alignmentMatch = [regex]::Match($loadLine.Line, 'align 2\*\*(\d+)')
            if (!$alignmentMatch.Success -or [int]$alignmentMatch.Groups[1].Value -lt 14) {
                throw "Native library is not 16 KB ELF aligned: $($nativeLibrary.Name)"
            }
        }
        $programHeaders = & $llvmReadelf -l $nativeLibrary.FullName 2>&1
        if ($LASTEXITCODE -ne 0 -or ($programHeaders -join "`n") -notmatch 'GNU_RELRO') {
            throw "Native library is missing GNU_RELRO: $($nativeLibrary.Name)"
        }
    }

    $playBundleLength = (Get-Item -LiteralPath $playBundlePath).Length
    if ($playBundleLength -ge 500MB) {
        throw 'The base Google Play App Bundle is at or above the 500 MB limit.'
    }

    $artifactDirectory = Join-Path $projectRoot "artifacts\releases\google-play\v$expectedVersionName"
    New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
    $artifactBundlePath = Join-Path $artifactDirectory 'LumaSR-0.3.4-google-play.aab'
    $artifactApkPath = Join-Path $artifactDirectory 'LumaSR-v0.3.4.apk'
    $artifactMappingPath = Join-Path $artifactDirectory 'mapping.txt'
    $artifactSymbolsPath = Join-Path $artifactDirectory 'native-debug-symbols.zip'
    $artifactPlayCertificatePath = Join-Path $artifactDirectory 'play-upload-certificate.pem'
    $artifactAppCertificatePath = Join-Path $artifactDirectory 'app-signing-certificate.pem'

    Copy-Item -LiteralPath $playBundlePath -Destination $artifactBundlePath -Force
    Copy-Item -LiteralPath $releaseApkPath -Destination $artifactApkPath -Force
    Copy-Item -LiteralPath $playMappingPath -Destination $artifactMappingPath -Force
    Copy-Item -LiteralPath $playNativeSymbolsPath -Destination $artifactSymbolsPath -Force
    Copy-Item -LiteralPath $playCertificatePath -Destination $artifactPlayCertificatePath -Force
    & keytool '-J-Duser.language=en' '-J-Duser.country=US' `
        -exportcert -rfc `
        -keystore $env:LUMASR_UPLOAD_STORE_FILE `
        '-storepass:env' LUMASR_UPLOAD_STORE_PASSWORD `
        -alias $env:LUMASR_UPLOAD_KEY_ALIAS `
        -file $artifactAppCertificatePath
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to export the public app signing certificate.'
    }

    $artifactFiles = @(
        $artifactBundlePath,
        $artifactApkPath,
        $artifactMappingPath,
        $artifactSymbolsPath,
        $artifactPlayCertificatePath,
        $artifactAppCertificatePath
    )
    $artifactRecords = foreach ($artifactFile in $artifactFiles) {
        $item = Get-Item -LiteralPath $artifactFile
        [ordered]@{
            name = $item.Name
            bytes = $item.Length
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $item.FullName).Hash.ToLowerInvariant()
        }
    }

    $checksumsPath = Join-Path $artifactDirectory 'checksums.sha256'
    $artifactRecords | ForEach-Object { "$($_.sha256)  $($_.name)" } |
        Set-Content -Encoding ASCII -LiteralPath $checksumsPath
    $releaseManifestPath = Join-Path $artifactDirectory 'release-manifest.json'
    [ordered]@{
        schemaVersion = 1
        packageName = $expectedPackageName
        versionName = $expectedVersionName
        versionCode = $expectedVersionCode
        minSdk = 29
        targetSdk = $expectedTargetSdk
        playUploadAsset = 'LumaSR-0.3.4-google-play.aab'
        installableAsset = 'LumaSR-v0.3.4.apk'
        playUploadCertificateSha256 = $expectedPlayCertificateSha256
        appSigningCertificateSha256 = $fixedAppSigningCertificateSha256
        pageAlignment = 'PAGE_ALIGNMENT_16K'
        elfLoadAlignment = '2**14-or-greater'
        zipAlignmentVerified = $true
        universalApkBytes = (Get-Item -LiteralPath $universalApkPath).Length
        estimatedCompressedDownloadBytesMin = $estimatedCompressedDownloadBytesMin
        estimatedCompressedDownloadBytesMax = $estimatedCompressedDownloadBytesMax
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        files = @($artifactRecords)
    } | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -LiteralPath $releaseManifestPath

    Write-Host "Google Play App Bundle: $artifactBundlePath"
    Write-Host "Installable Release APK: $artifactApkPath"
    Write-Host "Release manifest: $releaseManifestPath"
    Write-Host "Checksums: $checksumsPath"
} finally {
    foreach ($name in $allVariableNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
