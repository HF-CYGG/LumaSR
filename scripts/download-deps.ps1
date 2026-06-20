param(
    [string]$NdkZipUrl,
    [string]$CmakeZipUrl,
    [string]$NcnnAndroidVulkanZipUrl,
    [string]$Waifu2xCunetZipUrl,
    [string]$Waifu2xAnimeZipUrl,
    [string]$Waifu2xPhotoZipUrl,
    [string]$RealCuganStandardZipUrl,
    [string]$RealCuganProZipUrl,
    [string]$RealEsrganZipUrl,
    [string]$RealEsrganModelsZipUrl,
    [string]$RealCuganSourceZipUrl,
    [string]$Waifu2xRepoDir,
    [string]$RealCuganAssetsZip,
    [string]$NdkVersion = "27.2.12479018",
    [string]$CmakeVersion = "3.22.1",
    [string]$SdkRoot = "F:\android-sdk",
    [string]$OutputRoot = "F:\LumaSR\.deps",
    [switch]$ModelsOnly,
    [switch]$ValidateOnly
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RealEsrganZipUrl) -and ![string]::IsNullOrWhiteSpace($RealEsrganModelsZipUrl)) {
    $RealEsrganZipUrl = $RealEsrganModelsZipUrl
}

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$modelRoot = Join-Path $projectRoot "app\src\main\assets\models"

$modelPacks = @(
    [PSCustomObject]@{
        Id = "waifu2x-cunet"
        EngineDir = "waifu2x"
        ModelDir = "models-cunet"
        ZipUrl = $Waifu2xCunetZipUrl
        Upstream = "https://github.com/nihui/waifu2x-ncnn-vulkan/tree/master/models/models-cunet"
        Files = @(
            "noise0_model.bin", "noise0_model.param",
            "noise0_scale2.0x_model.bin", "noise0_scale2.0x_model.param",
            "noise1_model.bin", "noise1_model.param",
            "noise1_scale2.0x_model.bin", "noise1_scale2.0x_model.param",
            "noise2_model.bin", "noise2_model.param",
            "noise2_scale2.0x_model.bin", "noise2_scale2.0x_model.param",
            "noise3_model.bin", "noise3_model.param",
            "noise3_scale2.0x_model.bin", "noise3_scale2.0x_model.param",
            "scale2.0x_model.bin", "scale2.0x_model.param"
        )
    },
    [PSCustomObject]@{
        Id = "waifu2x-anime"
        EngineDir = "waifu2x"
        ModelDir = "models-upconv_7_anime_style_art_rgb"
        ZipUrl = $Waifu2xAnimeZipUrl
        Upstream = "https://github.com/nihui/waifu2x-ncnn-vulkan/tree/master/models/models-upconv_7_anime_style_art_rgb"
        Files = @(
            "noise0_scale2.0x_model.bin", "noise0_scale2.0x_model.param",
            "noise1_scale2.0x_model.bin", "noise1_scale2.0x_model.param",
            "noise2_scale2.0x_model.bin", "noise2_scale2.0x_model.param",
            "noise3_scale2.0x_model.bin", "noise3_scale2.0x_model.param",
            "scale2.0x_model.bin", "scale2.0x_model.param"
        )
    },
    [PSCustomObject]@{
        Id = "waifu2x-photo"
        EngineDir = "waifu2x"
        ModelDir = "models-upconv_7_photo"
        ZipUrl = $Waifu2xPhotoZipUrl
        Upstream = "https://github.com/nihui/waifu2x-ncnn-vulkan/tree/master/models/models-upconv_7_photo"
        Files = @(
            "noise0_scale2.0x_model.bin", "noise0_scale2.0x_model.param",
            "noise1_scale2.0x_model.bin", "noise1_scale2.0x_model.param",
            "noise2_scale2.0x_model.bin", "noise2_scale2.0x_model.param",
            "noise3_scale2.0x_model.bin", "noise3_scale2.0x_model.param",
            "scale2.0x_model.bin", "scale2.0x_model.param"
        )
    },
    [PSCustomObject]@{
        Id = "realcugan-standard"
        EngineDir = "realcugan"
        ModelDir = "models-se"
        ZipUrl = $RealCuganStandardZipUrl
        Upstream = "https://github.com/nihui/realcugan-ncnn-vulkan/tree/master/models/models-se"
        Files = @(
            "up2x-conservative.bin", "up2x-conservative.param",
            "up2x-denoise1x.bin", "up2x-denoise1x.param",
            "up2x-denoise2x.bin", "up2x-denoise2x.param",
            "up2x-denoise3x.bin", "up2x-denoise3x.param",
            "up2x-no-denoise.bin", "up2x-no-denoise.param",
            "up3x-conservative.bin", "up3x-conservative.param",
            "up3x-denoise3x.bin", "up3x-denoise3x.param",
            "up3x-no-denoise.bin", "up3x-no-denoise.param",
            "up4x-conservative.bin", "up4x-conservative.param",
            "up4x-denoise3x.bin", "up4x-denoise3x.param",
            "up4x-no-denoise.bin", "up4x-no-denoise.param"
        )
    },
    [PSCustomObject]@{
        Id = "realcugan-pro"
        EngineDir = "realcugan"
        ModelDir = "models-pro"
        ZipUrl = $RealCuganProZipUrl
        Upstream = "https://github.com/nihui/realcugan-ncnn-vulkan/tree/master/models/models-pro"
        Files = @(
            "up2x-conservative.bin", "up2x-conservative.param",
            "up2x-denoise3x.bin", "up2x-denoise3x.param",
            "up2x-no-denoise.bin", "up2x-no-denoise.param",
            "up3x-conservative.bin", "up3x-conservative.param",
            "up3x-denoise3x.bin", "up3x-denoise3x.param",
            "up3x-no-denoise.bin", "up3x-no-denoise.param"
        )
    },
    [PSCustomObject]@{
        Id = "realesrgan-general-x4"
        EngineDir = "realesrgan"
        ModelDir = ""
        ZipUrl = $RealEsrganZipUrl
        Upstream = "https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan/tree/master/models"
        Files = @(
            "realesrgan-x4plus.bin", "realesrgan-x4plus.param"
        )
    },
    [PSCustomObject]@{
        Id = "realesrgan-anime-x4"
        EngineDir = "realesrgan"
        ModelDir = ""
        ZipUrl = $RealEsrganZipUrl
        Upstream = "https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan/tree/master/models"
        Files = @(
            "realesrgan-x4plus-anime.bin", "realesrgan-x4plus-anime.param"
        )
    },
    [PSCustomObject]@{
        Id = "realesrgan-animevideo-x2"
        EngineDir = "realesrgan"
        ModelDir = ""
        ZipUrl = $RealEsrganZipUrl
        Upstream = "https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan/tree/master/models"
        Files = @(
            "realesr-animevideov3-x2.bin", "realesr-animevideov3-x2.param"
        )
    },
    [PSCustomObject]@{
        Id = "realesrgan-animevideo-x3"
        EngineDir = "realesrgan"
        ModelDir = ""
        ZipUrl = $RealEsrganZipUrl
        Upstream = "https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan/tree/master/models"
        Files = @(
            "realesr-animevideov3-x3.bin", "realesr-animevideov3-x3.param"
        )
    },
    [PSCustomObject]@{
        Id = "realesrgan-animevideo-x4"
        EngineDir = "realesrgan"
        ModelDir = ""
        ZipUrl = $RealEsrganZipUrl
        Upstream = "https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan/tree/master/models"
        Files = @(
            "realesr-animevideov3-x4.bin", "realesr-animevideov3-x4.param"
        )
    }
)

function Assert-DomesticUrl {
    param([string]$Url)
    $uri = [System.Uri]$Url
    $blockedHosts = @(
        "github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
        "githubusercontent.com",
        "dl.google.com",
        "storage.googleapis.com",
        "repo.maven.apache.org",
        "services.gradle.org"
    )
    foreach ($hostName in $blockedHosts) {
        if ($uri.Host.Equals($hostName, [System.StringComparison]::OrdinalIgnoreCase) -or $uri.Host.EndsWith(".$hostName", [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing overseas direct download URL: $Url"
        }
    }
}

function Assert-RequiredUrl {
    param(
        [string]$Name,
        [string]$Url
    )
    if ([string]::IsNullOrWhiteSpace($Url)) {
        throw "Missing domestic mirror URL for $Name"
    }
    Assert-DomesticUrl $Url
}

function Assert-PathInsideRoot {
    param(
        [string]$Path,
        [string]$Root
    )
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullRoot = [System.IO.Path]::GetFullPath($Root)
    if ($fullPath -ne $fullRoot -and !$fullPath.StartsWith($fullRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to write outside intended root: $fullPath"
    }
}

function Download-File {
    param(
        [string]$Url,
        [string]$Destination
    )
    Assert-DomesticUrl $Url
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
    Invoke-WebRequest -Uri $Url -OutFile $Destination
}

function Expand-SingleRootArchive {
    param(
        [string]$ZipPath,
        [string]$Destination
    )
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
    Expand-Archive -Force $ZipPath $temp
    $children = Get-ChildItem -Force $temp
    if ($children.Count -eq 1 -and $children[0].PSIsContainer) {
        if (Test-Path $Destination) {
            Assert-PathInsideRoot $Destination (Split-Path -Parent $Destination)
            Remove-Item -Recurse -Force $Destination
        }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
        Move-Item -Force $children[0].FullName $Destination
    } else {
        New-Item -ItemType Directory -Force -Path $Destination | Out-Null
        Copy-Item -Recurse -Force (Join-Path $temp "*") $Destination
    }
    Remove-Item -Recurse -Force $temp
}

function Get-ModelTargetPath {
    param([PSCustomObject]$ModelPack)
    return Join-Path $modelRoot (Join-Path $ModelPack.EngineDir $ModelPack.ModelDir)
}

function Validate-ModelDirectory {
    param(
        [PSCustomObject]$ModelPack,
        [string]$Target
    )
    foreach ($fileName in $ModelPack.Files) {
        $path = Join-Path $Target $fileName
        if (!(Test-Path $path -PathType Leaf)) {
            throw "Model $($ModelPack.Id) is missing required file: $fileName"
        }
    }
}

function Install-ModelPack {
    param(
        [PSCustomObject]$ModelPack,
        [string]$ZipPath
    )
    $target = Get-ModelTargetPath $ModelPack
    Assert-PathInsideRoot $target $modelRoot
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
    Expand-Archive -Force $ZipPath $temp

    New-Item -ItemType Directory -Force -Path $target | Out-Null

    foreach ($fileName in $ModelPack.Files) {
        $allMatches = @(Get-ChildItem -Path $temp -Recurse -File -Filter $fileName)
        $matches = @($allMatches | Where-Object { $_.FullName -like "*$($ModelPack.ModelDir)*" })
        if ($matches.Count -eq 0 -and $allMatches.Count -eq 1) {
            $matches = $allMatches
        }
        if ($matches.Count -eq 0) {
            throw "Archive for $($ModelPack.Id) does not contain required file: $fileName"
        }
        if ($matches.Count -gt 1) {
            throw "Archive for $($ModelPack.Id) contains duplicate file name: $fileName"
        }
        Copy-Item -Force $matches[0].FullName (Join-Path $target $fileName)
    }

    @(
        "Model: $($ModelPack.Id)",
        "Upstream: $($ModelPack.Upstream)",
        "Domestic mirror used by script: $($ModelPack.ZipUrl)",
        "Generated by scripts/download-deps.ps1"
    ) | Set-Content -Encoding UTF8 (Join-Path $target "SOURCE.txt")

    Remove-Item -Recurse -Force $temp
    Validate-ModelDirectory $ModelPack $target
}

function Install-ModelPackFromDirectory {
    param(
        [PSCustomObject]$ModelPack,
        [string]$SourceRoot,
        [string]$SourceLabel
    )
    $target = Get-ModelTargetPath $ModelPack
    Assert-PathInsideRoot $target $modelRoot
    if (!(Test-Path $SourceRoot -PathType Container)) {
        throw "Model source directory not found: $SourceRoot"
    }
    New-Item -ItemType Directory -Force -Path $target | Out-Null

    foreach ($fileName in $ModelPack.Files) {
        $allMatches = @(Get-ChildItem -Path $SourceRoot -Recurse -File -Filter $fileName)
        $matches = @($allMatches | Where-Object { $_.FullName -like "*$($ModelPack.ModelDir)*" })
        if ($matches.Count -eq 0 -and $allMatches.Count -eq 1) {
            $matches = $allMatches
        }
        if ($matches.Count -eq 0) {
            throw "Source for $($ModelPack.Id) does not contain required file: $fileName"
        }
        if ($matches.Count -gt 1) {
            throw "Source for $($ModelPack.Id) contains duplicate file name: $fileName"
        }
        Copy-Item -Force $matches[0].FullName (Join-Path $target $fileName)
    }

    @(
        "Model: $($ModelPack.Id)",
        "Upstream: $($ModelPack.Upstream)",
        "Source used by script: $SourceLabel",
        "Generated by scripts/download-deps.ps1"
    ) | Set-Content -Encoding UTF8 (Join-Path $target "SOURCE.txt")

    Validate-ModelDirectory $ModelPack $target
}

if ($ValidateOnly) {
    foreach ($modelPack in $modelPacks) {
        Validate-ModelDirectory $modelPack (Get-ModelTargetPath $modelPack)
    }
    Write-Host "All built-in model directories are complete."
    exit 0
}

if (!$ModelsOnly) {
    Assert-RequiredUrl "Android NDK" $NdkZipUrl
    Assert-RequiredUrl "CMake" $CmakeZipUrl
    Assert-RequiredUrl "ncnn Android Vulkan" $NcnnAndroidVulkanZipUrl
    if (![string]::IsNullOrWhiteSpace($RealCuganSourceZipUrl)) {
        Assert-RequiredUrl "RealCUGAN source" $RealCuganSourceZipUrl
    }
}
foreach ($modelPack in $modelPacks) {
    $hasLocalWaifu2x = $modelPack.EngineDir -eq "waifu2x" -and ![string]::IsNullOrWhiteSpace($Waifu2xRepoDir)
    $hasLocalRealCugan = $modelPack.EngineDir -eq "realcugan" -and ![string]::IsNullOrWhiteSpace($RealCuganAssetsZip)
    if (!$hasLocalWaifu2x -and !$hasLocalRealCugan) {
        Assert-RequiredUrl $modelPack.Id $modelPack.ZipUrl
    }
}

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

$downloads = Join-Path $OutputRoot "downloads"
$ncnnZip = Join-Path $downloads "ncnn-android-vulkan.zip"
$realCuganSourceZip = Join-Path $downloads "realcugan-source.zip"
$ndkZip = Join-Path $downloads "android-ndk.zip"
$cmakeZip = Join-Path $downloads "cmake.zip"

$nativeRoot = Join-Path $OutputRoot "native"
New-Item -ItemType Directory -Force -Path $nativeRoot | Out-Null
New-Item -ItemType Directory -Force -Path $modelRoot | Out-Null

$ndkDestination = Join-Path $SdkRoot "ndk\$NdkVersion"
$cmakeDestination = Join-Path $SdkRoot "cmake\$CmakeVersion"
if (!$ModelsOnly) {
    Download-File $NdkZipUrl $ndkZip
    Download-File $CmakeZipUrl $cmakeZip
    Download-File $NcnnAndroidVulkanZipUrl $ncnnZip
    Expand-SingleRootArchive $ndkZip $ndkDestination
    Expand-SingleRootArchive $cmakeZip $cmakeDestination
    Expand-Archive -Force $ncnnZip (Join-Path $nativeRoot "ncnn")
    if (![string]::IsNullOrWhiteSpace($RealCuganSourceZipUrl)) {
        Download-File $RealCuganSourceZipUrl $realCuganSourceZip
        Expand-SingleRootArchive $realCuganSourceZip (Join-Path $OutputRoot "sources\realcugan-ncnn-vulkan")
    }
}

foreach ($modelPack in $modelPacks) {
    if ($modelPack.EngineDir -eq "waifu2x" -and ![string]::IsNullOrWhiteSpace($Waifu2xRepoDir)) {
        Install-ModelPackFromDirectory $modelPack $Waifu2xRepoDir $Waifu2xRepoDir
    } elseif ($modelPack.EngineDir -eq "realcugan" -and ![string]::IsNullOrWhiteSpace($RealCuganAssetsZip)) {
        Install-ModelPack $modelPack $RealCuganAssetsZip
    } else {
        $zipPath = Join-Path $downloads "$($modelPack.Id).zip"
        Download-File $modelPack.ZipUrl $zipPath
        Install-ModelPack $modelPack $zipPath
    }
}

Write-Host "Downloaded and extracted domestic-mirror dependencies."
if (!$ModelsOnly) {
    Write-Host "NDK installed to $ndkDestination"
    Write-Host "CMake installed to $cmakeDestination"
}
Write-Host "Built-in models installed to $modelRoot"
Write-Host "Build native Debug APK with: .\gradlew.bat assembleDebug"
