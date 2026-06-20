# Built-in Model Setup

LumaSR bundles Waifu2x, RealCUGAN, and Real-ESRGAN model files from `app/src/main/assets/models`.
Do not add placeholder `.param` or `.bin` files. The APK should only include
real model files from trusted mirrors.

## Required Mirror Archives

Prepare domestic mirror URLs for these archives:

- Waifu2x CUnet: `models-cunet`
- Waifu2x Anime: `models-upconv_7_anime_style_art_rgb`
- Waifu2x Photo: `models-upconv_7_photo`
- RealCUGAN Standard: `models-se`
- RealCUGAN Pro: `models-pro`
- Real-ESRGAN models archive: `realesrgan-x4plus`, `realesrgan-x4plus-anime`, `realesr-animevideov3-x2/x3/x4`
- ncnn Android Vulkan SDK
- Android NDK zip
- CMake zip

The script rejects direct overseas hosts such as `github.com`, `dl.google.com`,
`repo.maven.apache.org`, and `services.gradle.org`. A domestic proxy URL is
accepted when its host is the mirror domain.

## Install

Model-only install when Android SDK, NDK, and CMake are already present:

```powershell
.\scripts\download-deps.ps1 -ModelsOnly `
  -Waifu2xCunetZipUrl "<domestic-waifu2x-repo-zip>" `
  -Waifu2xAnimeZipUrl "<domestic-waifu2x-repo-zip>" `
  -Waifu2xPhotoZipUrl "<domestic-waifu2x-repo-zip>" `
  -RealCuganStandardZipUrl "<domestic-realcugan-assets-zip>" `
  -RealCuganProZipUrl "<domestic-realcugan-assets-zip>" `
  -RealEsrganModelsZipUrl "<domestic-realesrgan-models-zip>"
```

Full dependency install:

```powershell
.\scripts\download-deps.ps1 `
  -NdkZipUrl "<domestic-ndk-zip>" `
  -CmakeZipUrl "<domestic-cmake-zip>" `
  -NcnnAndroidVulkanZipUrl "<domestic-ncnn-android-vulkan-zip>" `
  -Waifu2xCunetZipUrl "<domestic-waifu2x-cunet-zip>" `
  -Waifu2xAnimeZipUrl "<domestic-waifu2x-anime-zip>" `
  -Waifu2xPhotoZipUrl "<domestic-waifu2x-photo-zip>" `
  -RealCuganStandardZipUrl "<domestic-realcugan-standard-zip>" `
  -RealCuganProZipUrl "<domestic-realcugan-pro-zip>" `
  -RealEsrganModelsZipUrl "<domestic-realesrgan-models-zip>"
```

Each model archive may contain only the model directory or a larger repository
tree. The installer filters files by model directory name and writes only the
files listed in `model_manifest.json`.

## Built-in Model Matrix

- Waifu2x CUnet / Anime / Photo: 1x denoise-only and 2x upscale workflows.
- RealCUGAN Standard: 2x / 3x / 4x, matching the bundled `models-se` files.
- RealCUGAN Pro: 2x / 3x only; 4x is not exposed because the current `models-pro` assets do not include 4x files.
- Real-ESRGAN x4plus / x4plus anime: 4x only, using explicit `modelFileBase` names in `model_manifest.json`.
- Real-ESRGAN AnimeVideo v3: separate 2x / 3x / 4x model files, each exposed as a single-scale built-in model.

## Validate

```powershell
.\scripts\download-deps.ps1 -ValidateOnly
.\gradlew.bat -g F:\Development\gradle testDebugUnitTest
.\gradlew.bat -g F:\Development\gradle lintDebug
.\gradlew.bat -g F:\Development\gradle assembleDebug
```

The Android project now builds the JNI shell by default for `arm64-v8a`.
