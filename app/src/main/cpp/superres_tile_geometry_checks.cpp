#include "superres_tile_geometry.h"

namespace {

static_assert(output_pixels_within_budget(589, 1280, 4));
static_assert(!output_pixels_within_budget(589, 1280, 16));
static_assert(!output_pixels_within_budget(2147483647, 2, 2));

constexpr TileInputRegion kCunetInterior = make_waifu2x_tile_region(
    2,
    18,
    1081,
    1601,
    512,
    512,
    512,
    512
);

static_assert(kCunetInterior.inputX == 494);
static_assert(kCunetInterior.inputY == 494);
static_assert(kCunetInterior.inputW == 549);
static_assert(kCunetInterior.inputH == 549);
static_assert(kCunetInterior.cropX == 0);
static_assert(kCunetInterior.cropY == 0);
static_assert(kCunetInterior.dstX == 1024);
static_assert(kCunetInterior.dstY == 1024);
static_assert(kCunetInterior.copyW == 1024);
static_assert(kCunetInterior.copyH == 1024);

constexpr TileInputRegion kCunetRightBottom = make_waifu2x_tile_region(
    2,
    18,
    1081,
    1601,
    1024,
    1536,
    57,
    65
);

static_assert(kCunetRightBottom.inputX == 1006);
static_assert(kCunetRightBottom.inputY == 1518);
static_assert(kCunetRightBottom.inputW == 94);
static_assert(kCunetRightBottom.inputH == 102);
static_assert(kCunetRightBottom.cropX == 0);
static_assert(kCunetRightBottom.cropY == 0);
static_assert(kCunetRightBottom.dstX == 2048);
static_assert(kCunetRightBottom.dstY == 3072);
static_assert(kCunetRightBottom.copyW == 114);
static_assert(kCunetRightBottom.copyH == 130);

constexpr TileInputRegion kUpconvInterior = make_waifu2x_tile_region(
    2,
    7,
    1081,
    1601,
    512,
    512,
    512,
    512
);

static_assert(kUpconvInterior.inputW == 527);
static_assert(kUpconvInterior.inputH == 527);
static_assert(kUpconvInterior.cropX == 0);
static_assert(kUpconvInterior.cropY == 0);

constexpr TileInputRegion kCunetDenoiseOnly = make_waifu2x_tile_region(
    1,
    28,
    1081,
    1601,
    512,
    512,
    512,
    512
);

static_assert(kCunetDenoiseOnly.inputW == 571);
static_assert(kCunetDenoiseOnly.inputH == 571);
static_assert(kCunetDenoiseOnly.cropX == 0);
static_assert(kCunetDenoiseOnly.cropY == 0);
static_assert(kCunetDenoiseOnly.copyW == 512);
static_assert(kCunetDenoiseOnly.copyH == 512);

constexpr TileInputRegion kRealCugan2xFirstTile = make_realcugan_tile_region(
    2,
    18,
    751,
    1182,
    0,
    0,
    128,
    128
);

static_assert(kRealCugan2xFirstTile.inputX == -18);
static_assert(kRealCugan2xFirstTile.inputY == -18);
static_assert(kRealCugan2xFirstTile.inputW == 164);
static_assert(kRealCugan2xFirstTile.inputH == 164);
static_assert(kRealCugan2xFirstTile.cropX == 0);
static_assert(kRealCugan2xFirstTile.cropY == 0);
static_assert(kRealCugan2xFirstTile.dstX == 0);
static_assert(kRealCugan2xFirstTile.dstY == 0);
static_assert(kRealCugan2xFirstTile.copyW == 256);
static_assert(kRealCugan2xFirstTile.copyH == 256);

constexpr TileInputRegion kRealCugan3xRightBottom = make_realcugan_tile_region(
    3,
    14,
    751,
    1182,
    640,
    1152,
    111,
    30
);

static_assert(kRealCugan3xRightBottom.inputX == 626);
static_assert(kRealCugan3xRightBottom.inputY == 1138);
static_assert(kRealCugan3xRightBottom.inputW == 140);
static_assert(kRealCugan3xRightBottom.inputH == 60);
static_assert(kRealCugan3xRightBottom.cropX == 0);
static_assert(kRealCugan3xRightBottom.cropY == 0);
static_assert(kRealCugan3xRightBottom.dstX == 1920);
static_assert(kRealCugan3xRightBottom.dstY == 3456);
static_assert(kRealCugan3xRightBottom.copyW == 333);
static_assert(kRealCugan3xRightBottom.copyH == 90);

static_assert(tile_output_has_rgb_channels(1, 4));
static_assert(tile_output_has_rgb_channels(3, 1));
static_assert(!tile_output_has_rgb_channels(1, 1));
static_assert(tile_output_needs_unpacking(1, 4));
static_assert(!tile_output_needs_unpacking(3, 1));
static_assert(tile_output_needs_fp32_conversion(2, 1));
static_assert(!tile_output_needs_fp32_conversion(4, 1));
static_assert(resolve_waifu2x_output_crop(1024, 1024) == 0);
static_assert(resolve_waifu2x_output_crop(1098, 1024) == 0);
static_assert(resolve_waifu2x_output_crop(1000, 1024) == -1);
static_assert(resolve_tile_output_crop(1098, 1024, 36) == 36);
static_assert(resolve_tile_output_crop(1024, 1024, 36) == 0);
static_assert(resolve_tile_output_crop(1030, 1024, 36) == 6);
static_assert(resolve_tile_output_crop(1000, 1024, 36) == -1);
static_assert(realcugan_4x_residual_source_coord(0, 16) == 0);
static_assert(realcugan_4x_residual_source_coord(3, 16) == 0);
static_assert(realcugan_4x_residual_source_coord(4, 16) == 1);
static_assert(realcugan_4x_residual_source_coord(67, 16) == 15);

constexpr CroppedCopyWindow kInteriorCropWindow = resolve_cropped_copy_window(
    10,
    20,
    120,
    200,
    100,
    80,
    120,
    200,
    100,
    80
);

static_assert(kInteriorCropWindow.valid);
static_assert(kInteriorCropWindow.srcX == 10);
static_assert(kInteriorCropWindow.srcY == 20);
static_assert(kInteriorCropWindow.dstX == 0);
static_assert(kInteriorCropWindow.dstY == 0);
static_assert(kInteriorCropWindow.copyW == 100);
static_assert(kInteriorCropWindow.copyH == 80);

constexpr CroppedCopyWindow kPartialCropWindow = resolve_cropped_copy_window(
    0,
    0,
    64,
    96,
    120,
    90,
    100,
    120,
    100,
    80
);

static_assert(kPartialCropWindow.valid);
static_assert(kPartialCropWindow.srcX == 36);
static_assert(kPartialCropWindow.srcY == 24);
static_assert(kPartialCropWindow.dstX == 0);
static_assert(kPartialCropWindow.dstY == 0);
static_assert(kPartialCropWindow.copyW == 84);
static_assert(kPartialCropWindow.copyH == 66);

constexpr CroppedCopyWindow kOutsideCropWindow = resolve_cropped_copy_window(
    0,
    0,
    0,
    0,
    20,
    20,
    100,
    100,
    40,
    40
);

static_assert(!kOutsideCropWindow.valid);
static_assert(should_retry_cpu_after_gpu_code(0, 8));
static_assert(should_retry_cpu_after_gpu_code(0, 9));
static_assert(!should_retry_cpu_after_gpu_code(1, 9));
static_assert(!should_retry_cpu_after_gpu_code(0, 5));
static_assert(!should_force_cpu_before_vulkan_model_load(0));
static_assert(!should_force_cpu_before_vulkan_model_load(1));
static_assert(is_large_realesrgan_model_base("realesrgan-x4plus"));
static_assert(is_large_realesrgan_model_base("realesrgan-x4plus-anime"));
static_assert(!is_large_realesrgan_model_base("realesr-animevideov3-x2"));
static_assert(is_waifu2x_cunet_dir("models/waifu2x/models-cunet"));
static_assert(is_waifu2x_cunet_dir("/data/user/0/com.lumasr/cache/models/waifu2x-cunet"));
static_assert(!is_waifu2x_cunet_dir("/data/user/0/com.lumasr/cache/models/waifu2x-photo"));
static_assert(is_waifu2x_upconv_dir("models/waifu2x/models-upconv_7_anime_style_art_rgb"));
static_assert(is_waifu2x_upconv_dir("/data/user/0/com.lumasr/cache/models/waifu2x-anime"));
static_assert(is_waifu2x_upconv_dir("/data/user/0/com.lumasr/cache/models/waifu2x-photo"));
static_assert(!is_waifu2x_upconv_dir("/data/user/0/com.lumasr/cache/models/waifu2x-cunet"));

}
