# Native ncnn Inference

LumaSR now uses the bundled ncnn Android Vulkan SDK for real Waifu2x, RealCUGAN, and Real-ESRGAN inference. The native layer decodes an input image, splits it into tiles, runs the selected `.param/.bin` model with ncnn, stitches tiles, and writes a PNG result.

## Required Native Toolchain

- Android NDK `27.2.12479018` or newer is required for `ncnn-20260526-android-vulkan`.
- NDK `25.1.8937393` is not compatible with this ncnn package because the prebuilt static libraries require newer libc++ ABI symbols.
- Use a domestic mirror URL for every download. Do not use direct `dl.google.com` or direct GitHub URLs.

Example dependency command:

```powershell
.\scripts\download-deps.ps1 `
  -NdkZipUrl "<domestic-ndk-r27c-windows.zip-url>" `
  -CmakeZipUrl "<domestic-cmake-3.22.1-windows.zip-url>" `
  -NcnnAndroidVulkanZipUrl "https://ghproxy.net/https://github.com/Tencent/ncnn/releases/download/20260526/ncnn-20260526-android-vulkan.zip" `
  -RealCuganSourceZipUrl "https://ghproxy.net/https://github.com/nihui/realcugan-ncnn-vulkan/archive/refs/tags/20220728.zip" `
  -RealEsrganModelsZipUrl "<domestic-realesrgan-models-zip>" `
  -Waifu2xRepoDir "F:\LumaSR\.deps\sources\waifu2x-ncnn-vulkan" `
  -RealCuganAssetsZip "F:\LumaSR\.deps\downloads\realcugan-assets.zip"
```

Real-ESRGAN models are selected by explicit `modelFileBase` values from the manifest. The first supported set includes `realesrgan-x4plus`, `realesrgan-x4plus-anime`, and `realesr-animevideov3-x2/x3/x4`.

After the compatible NDK is installed:

```powershell
.\gradlew.bat -g F:\Development\gradle testDebugUnitTest
.\gradlew.bat -g F:\Development\gradle assembleDebug
```
