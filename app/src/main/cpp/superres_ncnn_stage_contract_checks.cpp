#include "superres_ncnn_stage_contract_checks.h"

#include "superres_ncnn_stage_contract.h"

#include <limits>

namespace {

bool ordinary_output_geometry_check() {
    const SuperResNcnnStageGeometry geometry = resolve_superres_ncnn_stage_geometry(
        65,
        33,
        2,
        64,
        SuperResNcnnStageCrop{}
    );
    return geometry.status == SuperResNcnnStageGeometryStatus::Ok &&
        geometry.tileSize == 64 && geometry.tileOverlap == 16 &&
        geometry.maximumBandHeight == 128 && geometry.tilesX == 2 &&
        geometry.tilesY == 1 && geometry.totalTiles == 2 &&
        geometry.outputWidth == 130 && geometry.outputHeight == 66 &&
        geometry.bufferWidth == 130 && geometry.bufferHeight == 66 &&
        geometry.expectedCoveredPixels == 8580;
}

bool cropped_output_geometry_check() {
    const SuperResNcnnStageGeometry geometry = resolve_superres_ncnn_stage_geometry(
        65,
        33,
        2,
        64,
        SuperResNcnnStageCrop{true, 25, 10, 40, 20}
    );
    return geometry.status == SuperResNcnnStageGeometryStatus::Ok &&
        geometry.outputWidth == 130 && geometry.outputHeight == 66 &&
        geometry.bufferWidth == 40 && geometry.bufferHeight == 20 &&
        geometry.expectedCoveredPixels == 800;
}

bool invalid_and_overflow_geometry_check() {
    const SuperResNcnnStageGeometry invalidCrop = resolve_superres_ncnn_stage_geometry(
        65,
        33,
        2,
        64,
        SuperResNcnnStageCrop{true, 100, 10, 40, 20}
    );
    const SuperResNcnnStageGeometry tileScaleOverflow = resolve_superres_ncnn_stage_geometry(
        1,
        1,
        2,
        std::numeric_limits<int>::max(),
        SuperResNcnnStageCrop{}
    );
    const SuperResNcnnStageGeometry outputScaleOverflow = resolve_superres_ncnn_stage_geometry(
        std::numeric_limits<int>::max(),
        1,
        2,
        32,
        SuperResNcnnStageCrop{}
    );
    return invalidCrop.status == SuperResNcnnStageGeometryStatus::InvalidParams &&
        tileScaleOverflow.status == SuperResNcnnStageGeometryStatus::InvalidParams &&
        outputScaleOverflow.status == SuperResNcnnStageGeometryStatus::OutputTooLarge;
}

bool minimum_band_and_checked_products_check() {
    const SuperResNcnnStageGeometry geometry = resolve_superres_ncnn_stage_geometry(
        17,
        9,
        4,
        8,
        SuperResNcnnStageCrop{}
    );
    int totalTiles = 0;
    long long coverage = 0;
    return geometry.status == SuperResNcnnStageGeometryStatus::Ok &&
        geometry.tileSize == 32 && geometry.maximumBandHeight == 128 &&
        checked_superres_ncnn_stage_total_tiles(2, 3, totalTiles) && totalTiles == 6 &&
        !checked_superres_ncnn_stage_total_tiles(
            std::numeric_limits<int>::max(),
            2,
            totalTiles
        ) &&
        checked_superres_ncnn_stage_coverage(130, 66, coverage) && coverage == 8580 &&
        !checked_superres_ncnn_stage_coverage(
            std::numeric_limits<long long>::max(),
            2,
            coverage
        );
}

} // namespace

bool run_superres_ncnn_stage_contract_runtime_checks() {
    return ordinary_output_geometry_check() && cropped_output_geometry_check() &&
        invalid_and_overflow_geometry_check() && minimum_band_and_checked_products_check();
}
