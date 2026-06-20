#include "superres_tile_geometry.h"

namespace {

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

}
