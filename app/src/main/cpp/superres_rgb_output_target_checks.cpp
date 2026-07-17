#include "superres_rgb_output_target_checks.h"

#include "superres_output_finalizer.h"
#include "superres_pixel_io.h"
#include "superres_rgb_output_target.h"
#include "superres_stbi_decode.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "stb_image.h"

namespace {

bool file_exists(const std::string& path) {
    return std::ifstream(path, std::ios::binary).good();
}

std::vector<unsigned char> read_bytes(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    return std::vector<unsigned char>(
        std::istreambuf_iterator<char>(input),
        std::istreambuf_iterator<char>()
    );
}

bool write_bytes(const std::string& path, const std::vector<unsigned char>& bytes) {
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    output.write(
        reinterpret_cast<const char*>(bytes.data()),
        static_cast<std::streamsize>(bytes.size())
    );
    return output.good();
}

void release_check_pixels(unsigned char* pixels) noexcept {
    stbi_image_free(pixels);
}

bool decoded_pixels_equal(
    const std::string& path,
    int width,
    int height,
    const std::vector<unsigned char>& expected
) {
    const SuperResRgbDecodeResult decoded = decode_superres_rgb_image(
        path,
        SuperResStbiApi{stbi_load, stbi_failure_reason, release_check_pixels}
    );
    return decoded.status == SuperResRgbDecodeStatus::Ok &&
        decoded.image.width() == width && decoded.image.height() == height &&
        decoded.image.byte_size() == expected.size() &&
        std::equal(expected.begin(), expected.end(), decoded.image.data());
}

bool populate_production_png_target(
    RgbOutputTarget& target,
    const std::string& outputPath,
    int width,
    int height,
    const std::vector<unsigned char>& pixels
) {
    if (pixels.size() != static_cast<size_t>(width) * static_cast<size_t>(height) * 3u ||
        target.initialize(outputPath, width, height, 1) != SuperResNativeCode::Ok) {
        return false;
    }
    const size_t rowBytes = static_cast<size_t>(width) * 3u;
    for (int rowIndex = 0; rowIndex < height; ++rowIndex) {
        if (!target.prepare_window(rowIndex, 1)) {
            return false;
        }
        unsigned char* destination = target.row(rowIndex, 0);
        if (destination == nullptr) {
            return false;
        }
        const unsigned char* source =
            pixels.data() + static_cast<size_t>(rowIndex) * rowBytes;
        std::copy_n(source, rowBytes, destination);
    }
    return true;
}

bool memory_target_writes_and_stages_rgb_check() {
    const std::string finalPath = "superres-rgb-target-memory.out";
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());

    MemoryRgbOutput target;
    if (target.initialize(finalPath, 3, 2, 1) != SuperResNativeCode::Ok ||
        !target.prepare_window(0, 1)) {
        return false;
    }
    unsigned char* firstRow = target.row(0, 0);
    if (firstRow == nullptr) {
        return false;
    }
    const unsigned char first[] = {1, 2, 3, 4, 5, 6, 7, 8, 9};
    std::copy(std::begin(first), std::end(first), firstRow);

    if (!target.prepare_window(1, 1)) {
        return false;
    }
    unsigned char* secondRow = target.row(1, 0);
    if (secondRow == nullptr) {
        return false;
    }
    const unsigned char second[] = {10, 11, 12, 13, 14, 15, 16, 17, 18};
    std::copy(std::begin(second), std::end(second), secondRow);

    SuperResStagedOutput staged(finalPath);
    const SuperResNativeCode finishCode = target.finish_attempt(staged);
    const std::vector<unsigned char> expected{
        1, 2, 3, 4, 5, 6, 7, 8, 9,
        10, 11, 12, 13, 14, 15, 16, 17, 18
    };
    const bool passed = finishCode == SuperResNativeCode::Ok &&
        target.failure_code() == SuperResNativeCode::Ok &&
        target.encoding_duration() >= std::chrono::steady_clock::duration{} &&
        read_bytes(staged.temporary_path()) == expected && !file_exists(finalPath);
    staged.abort();
    std::remove(finalPath.c_str());
    return passed;
}

bool memory_target_maps_staging_failure_to_output_failed_check() {
    MemoryRgbOutput target;
    if (target.initialize("", 1, 1, 1) != SuperResNativeCode::Ok ||
        !target.prepare_window(0, 1)) {
        return false;
    }
    unsigned char* row = target.row(0, 0);
    if (row == nullptr) {
        return false;
    }
    row[0] = 1;
    row[1] = 2;
    row[2] = 3;
    SuperResStagedOutput staged("");
    return target.finish_attempt(staged) == SuperResNativeCode::OutputFailed &&
        target.failure_code() == SuperResNativeCode::OutputFailed;
}

bool output_target_keeps_callback_before_failed_publish_check() {
    const std::string finalPath = "superres-rgb-target-publish-failure.out";
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    if (!write_bytes(finalPath, {'o', 'l', 'd'})) {
        return false;
    }
    SuperResTaskRegistry registry;
    if (!registry.register_task("target-publish-failure")) {
        return false;
    }
    MemoryRgbOutput target;
    if (target.initialize(finalPath, 1, 1, 1) != SuperResNativeCode::Ok ||
        !target.prepare_window(0, 1)) {
        return false;
    }
    unsigned char* row = target.row(0, 0);
    if (row == nullptr) {
        return false;
    }
    row[0] = 1;
    row[1] = 2;
    row[2] = 3;
    SuperResStagedOutput staged(finalPath);
    if (target.finish_attempt(staged) != SuperResNativeCode::Ok) {
        return false;
    }
    bool callbackSawOldFinalAndPart = false;
    int renameCount = 0;
    const SuperResAtomicPublishOps failingRename{
        [&renameCount](const char*, const char*) {
            ++renameCount;
            return -1;
        },
        [](const std::string&) { return true; },
        [](const char* path) { return std::remove(path); }
    };
    const SuperResFinalizationResult result = finalize_superres_output_after_performance(
        staged,
        registry,
        "target-publish-failure",
        [&]() {
            callbackSawOldFinalAndPart =
                read_bytes(finalPath) == std::vector<unsigned char>{'o', 'l', 'd'} &&
                read_bytes(staged.temporary_path()) == std::vector<unsigned char>{1, 2, 3};
        },
        failingRename
    );
    registry.complete_task("target-publish-failure");
    const bool passed = result.outcome == SuperResTaskPublishOutcome::Failed &&
        callbackSawOldFinalAndPart && renameCount == 1 &&
        read_bytes(finalPath) == std::vector<unsigned char>{'o', 'l', 'd'} &&
        !file_exists(finalPath + ".part");
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    return passed;
}

bool output_target_cancel_keeps_old_final_without_rename_check() {
    const std::string finalPath = "superres-rgb-target-cancel.out";
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    if (!write_bytes(finalPath, {'o', 'l', 'd'})) {
        return false;
    }
    SuperResTaskRegistry registry;
    if (!registry.register_task("target-cancel")) {
        return false;
    }
    MemoryRgbOutput target;
    if (target.initialize(finalPath, 1, 1, 1) != SuperResNativeCode::Ok ||
        !target.prepare_window(0, 1)) {
        return false;
    }
    unsigned char* row = target.row(0, 0);
    if (row == nullptr) {
        return false;
    }
    row[0] = 4;
    row[1] = 5;
    row[2] = 6;
    SuperResStagedOutput staged(finalPath);
    if (target.finish_attempt(staged) != SuperResNativeCode::Ok) {
        return false;
    }
    registry.cancel("target-cancel");
    int renameCount = 0;
    const SuperResAtomicPublishOps operations{
        [&renameCount](const char*, const char*) {
            ++renameCount;
            return 0;
        },
        [](const std::string&) { return true; },
        [](const char* path) { return std::remove(path); }
    };
    const SuperResFinalizationResult result = finalize_superres_output_after_performance(
        staged,
        registry,
        "target-cancel",
        []() {},
        operations
    );
    registry.complete_task("target-cancel");
    const bool passed = result.outcome == SuperResTaskPublishOutcome::Cancelled &&
        renameCount == 0 && read_bytes(finalPath) == std::vector<unsigned char>{'o', 'l', 'd'} &&
        !file_exists(finalPath + ".part");
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    return passed;
}

struct RecordingWriter {
    std::vector<std::string> calls;
    std::vector<std::vector<unsigned char>> bands;
    std::string temporaryPath;
    size_t rowBytes = 0;
    bool failFinish = false;
    bool abortCalled = false;
};

StreamingPngOutputOps recording_ops(const std::shared_ptr<RecordingWriter>& writer) {
    return StreamingPngOutputOps{
        [writer](const std::string& outputPath, int width, int height) {
            writer->calls.push_back(
                "open:" + outputPath + ":" + std::to_string(width) + "x" +
                std::to_string(height)
            );
            writer->temporaryPath = outputPath + ".part";
            writer->rowBytes = static_cast<size_t>(width) * 3u;
            return write_bytes(writer->temporaryPath, {0})
                ? StreamingPngStatus::Ok
                : StreamingPngStatus::IoError;
        },
        [writer](const unsigned char* rgb, int rowCount, size_t rowStride) {
            writer->calls.push_back("rows:" + std::to_string(rowCount));
            if (rgb == nullptr || rowStride < writer->rowBytes || rowCount <= 0) {
                return StreamingPngStatus::InvalidParams;
            }
            std::vector<unsigned char> band;
            for (int row = 0; row < rowCount; ++row) {
                const unsigned char* begin = rgb + static_cast<size_t>(row) * rowStride;
                band.insert(band.end(), begin, begin + writer->rowBytes);
            }
            writer->bands.push_back(std::move(band));
            return StreamingPngStatus::Ok;
        },
        [writer]() {
            writer->calls.push_back("finish");
            if (writer->failFinish) {
                return StreamingPngStatus::IoError;
            }
            const std::vector<unsigned char> marker{0x89, 0x50, 0x4e, 0x47};
            return write_bytes(writer->temporaryPath, marker)
                ? StreamingPngStatus::Ok
                : StreamingPngStatus::IoError;
        },
        [writer]() {
            writer->abortCalled = true;
            std::remove(writer->temporaryPath.c_str());
        }
    };
}

bool streaming_target_flushes_windows_in_row_order_check() {
    const std::string finalPath = "superres-rgb-target-stream.out";
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    const auto writer = std::make_shared<RecordingWriter>();
    {
        StreamingPngOutput target(recording_ops(writer));
        if (target.initialize(finalPath, 2, 3, 2) != SuperResNativeCode::Ok ||
            !target.prepare_window(0, 2)) {
            return false;
        }
        unsigned char* firstBand = target.row(0, 0);
        unsigned char* secondBand = target.row(1, 0);
        if (firstBand == nullptr || secondBand == nullptr) {
            return false;
        }
        const unsigned char first[] = {1, 2, 3, 4, 5, 6};
        const unsigned char second[] = {7, 8, 9, 10, 11, 12};
        std::copy(std::begin(first), std::end(first), firstBand);
        std::copy(std::begin(second), std::end(second), secondBand);

        if (!target.prepare_window(2, 1)) {
            return false;
        }
        unsigned char* thirdBand = target.row(2, 0);
        if (thirdBand == nullptr) {
            return false;
        }
        const unsigned char third[] = {13, 14, 15, 16, 17, 18};
        std::copy(std::begin(third), std::end(third), thirdBand);

        SuperResStagedOutput staged(finalPath);
        if (target.finish_attempt(staged) != SuperResNativeCode::Ok ||
            !file_exists(staged.temporary_path())) {
            return false;
        }
        staged.abort();
    }
    const std::vector<std::string> expectedCalls{
        "open:" + finalPath + ":2x3", "rows:2", "rows:1", "finish"
    };
    const std::vector<std::vector<unsigned char>> expectedBands{
        {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
        {13, 14, 15, 16, 17, 18}
    };
    std::remove(finalPath.c_str());
    return writer->calls == expectedCalls && writer->bands == expectedBands &&
        writer->abortCalled;
}

bool streaming_target_rejects_out_of_order_and_out_of_bounds_windows_check() {
    const auto writer = std::make_shared<RecordingWriter>();
    StreamingPngOutput target(recording_ops(writer));
    if (target.initialize("superres-rgb-target-bounds.out", 2, 3, 2) !=
        SuperResNativeCode::Ok) {
        return false;
    }
    if (target.row(0, 0) != nullptr || target.prepare_window(1, 1) ||
        target.prepare_window(0, 3) || !target.prepare_window(0, 2) ||
        target.prepare_window(0, 1) || target.row(-1, 0) != nullptr ||
        target.row(2, 0) != nullptr || target.row(0, -1) != nullptr ||
        target.row(0, 2) != nullptr) {
        return false;
    }
    return !target.prepare_window(3, 1) && target.failure_code() == SuperResNativeCode::Ok;
}

bool streaming_target_preserves_zero_band_failure_code_check() {
    const auto writer = std::make_shared<RecordingWriter>();
    StreamingPngOutput target(recording_ops(writer));
    return target.initialize("superres-rgb-target-zero-band.out", 1, 1, 0) ==
            SuperResNativeCode::OutOfMemory &&
        target.failure_code() == SuperResNativeCode::OutOfMemory;
}

bool streaming_target_finish_failure_is_reported_and_cleaned_check() {
    const std::string finalPath = "superres-rgb-target-finish-failure.out";
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    const auto writer = std::make_shared<RecordingWriter>();
    writer->failFinish = true;
    SuperResNativeCode finishCode = SuperResNativeCode::Ok;
    {
        StreamingPngOutput target(recording_ops(writer));
        if (target.initialize(finalPath, 1, 1, 1) != SuperResNativeCode::Ok ||
            !target.prepare_window(0, 1) || target.row(0, 0) == nullptr) {
            return false;
        }
        target.row(0, 0)[0] = 1;
        target.row(0, 0)[1] = 2;
        target.row(0, 0)[2] = 3;
        SuperResStagedOutput staged(finalPath);
        finishCode = target.finish_attempt(staged);
        if (target.failure_code() != SuperResNativeCode::OutputFailed) {
            return false;
        }
    }
    const bool passed = finishCode == SuperResNativeCode::OutputFailed && writer->abortCalled &&
        !file_exists(finalPath + ".part") && !file_exists(finalPath);
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    return passed;
}

bool existing_rgb8_quantization_is_preserved_check() {
    MemoryRgbOutput target;
    if (target.initialize("superres-rgb-target-quantized.out", 3, 1, 1) !=
            SuperResNativeCode::Ok ||
        !target.prepare_window(0, 1)) {
        return false;
    }
    const float red[] = {-0.1f, 0.499f, 1.1f};
    const float green[] = {0.0f, 0.5f, 1.0f};
    const float blue[] = {0.001f, 0.501f, 0.999f};
    unsigned char* destination = target.row(0, 0);
    if (!copy_planar_float_row_to_rgb8(
            red, green, blue, destination, 3, nullptr, 0, 0)) {
        return false;
    }
    const unsigned char expected[] = {0, 0, 0, 127, 128, 128, 255, 255, 255};
    return std::equal(std::begin(expected), std::end(expected), destination);
}

bool production_streaming_target_publishes_decodable_png_check() {
    const std::string finalPath = "superres-rgb-target-production.png";
    const std::string taskId = "rgb-target-production-success";
    const std::vector<unsigned char> expected{
        1, 2, 3, 4, 5, 6,
        7, 8, 9, 10, 11, 12
    };
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    SuperResTaskPublishOutcome outcome = SuperResTaskPublishOutcome::Failed;
    {
        auto target = make_streaming_png_output();
        SuperResTaskRegistry registry;
        if (target == nullptr || !registry.register_task(taskId) ||
            !populate_production_png_target(*target, finalPath, 2, 2, expected)) {
            std::remove((finalPath + ".part").c_str());
            std::remove(finalPath.c_str());
            return false;
        }
        SuperResStagedOutput staged(finalPath);
        if (target->finish_attempt(staged) != SuperResNativeCode::Ok) {
            registry.complete_task(taskId);
            return false;
        }
        outcome = finalize_superres_output_after_performance(
            staged, registry, taskId, []() {}
        ).outcome;
        registry.complete_task(taskId);
    }
    const bool passed = outcome == SuperResTaskPublishOutcome::Published &&
        file_exists(finalPath) && !file_exists(finalPath + ".part") &&
        decoded_pixels_equal(finalPath, 2, 2, expected);
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    return passed;
}

bool production_streaming_target_late_callback_preserves_old_final_check() {
    const std::string finalPath = "superres-rgb-target-production-callback.png";
    const std::string taskId = "rgb-target-production-callback";
    const std::vector<unsigned char> sentinel{'o', 'l', 'd'};
    const std::vector<unsigned char> pixels{1, 2, 3};
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    if (!write_bytes(finalPath, sentinel)) {
        return false;
    }
    bool threw = false;
    {
        auto target = make_streaming_png_output();
        SuperResTaskRegistry registry;
        if (target == nullptr || !registry.register_task(taskId) ||
            !populate_production_png_target(*target, finalPath, 1, 1, pixels)) {
            std::remove(finalPath.c_str());
            return false;
        }
        SuperResStagedOutput staged(finalPath);
        if (target->finish_attempt(staged) != SuperResNativeCode::Ok) {
            registry.complete_task(taskId);
            std::remove(finalPath.c_str());
            return false;
        }
        try {
            (void)finalize_superres_output_after_performance(
                staged,
                registry,
                taskId,
                []() { throw std::runtime_error("late callback"); }
            );
        } catch (const std::runtime_error&) {
            threw = true;
        }
        registry.complete_task(taskId);
    }
    const bool passed = threw && read_bytes(finalPath) == sentinel &&
        !file_exists(finalPath + ".part");
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    return passed;
}

bool production_streaming_target_cancel_preserves_old_final_check() {
    const std::string finalPath = "superres-rgb-target-production-cancel.png";
    const std::string taskId = "rgb-target-production-cancel";
    const std::vector<unsigned char> sentinel{'o', 'l', 'd'};
    const std::vector<unsigned char> pixels{1, 2, 3};
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    if (!write_bytes(finalPath, sentinel)) {
        return false;
    }
    SuperResTaskPublishOutcome outcome = SuperResTaskPublishOutcome::Failed;
    {
        auto target = make_streaming_png_output();
        SuperResTaskRegistry registry;
        if (target == nullptr || !registry.register_task(taskId) ||
            !populate_production_png_target(*target, finalPath, 1, 1, pixels)) {
            std::remove(finalPath.c_str());
            return false;
        }
        SuperResStagedOutput staged(finalPath);
        if (target->finish_attempt(staged) != SuperResNativeCode::Ok) {
            registry.complete_task(taskId);
            std::remove(finalPath.c_str());
            return false;
        }
        registry.cancel(taskId);
        outcome = finalize_superres_output_after_performance(
            staged, registry, taskId, []() {}
        ).outcome;
        registry.complete_task(taskId);
    }
    const bool passed = outcome == SuperResTaskPublishOutcome::Cancelled &&
        read_bytes(finalPath) == sentinel && !file_exists(finalPath + ".part");
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    return passed;
}

bool production_streaming_target_rename_failure_preserves_old_final_check() {
    const std::string finalPath = "superres-rgb-target-production-rename.png";
    const std::string taskId = "rgb-target-production-rename";
    const std::vector<unsigned char> sentinel{'o', 'l', 'd'};
    const std::vector<unsigned char> pixels{1, 2, 3};
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    if (!write_bytes(finalPath, sentinel)) {
        return false;
    }
    SuperResTaskPublishOutcome outcome = SuperResTaskPublishOutcome::Published;
    {
        auto target = make_streaming_png_output();
        SuperResTaskRegistry registry;
        if (target == nullptr || !registry.register_task(taskId) ||
            !populate_production_png_target(*target, finalPath, 1, 1, pixels)) {
            std::remove(finalPath.c_str());
            return false;
        }
        SuperResStagedOutput staged(finalPath);
        if (target->finish_attempt(staged) != SuperResNativeCode::Ok) {
            registry.complete_task(taskId);
            std::remove(finalPath.c_str());
            return false;
        }
        const SuperResAtomicPublishOps failingOps{
            [](const char*, const char*) { return -1; },
            [](const std::string&) { return true; },
            [](const char* path) { return std::remove(path); }
        };
        outcome = finalize_superres_output_after_performance(
            staged, registry, taskId, []() {}, failingOps
        ).outcome;
        registry.complete_task(taskId);
    }
    const bool passed = outcome == SuperResTaskPublishOutcome::Failed &&
        read_bytes(finalPath) == sentinel && !file_exists(finalPath + ".part");
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    return passed;
}

bool memory_target_stages_tight_cropped_bytes_check() {
    const std::string finalPath = "superres-rgb-target-tight-crop.raw";
    const std::vector<unsigned char> expected{9, 8, 7, 6, 5, 4};
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    MemoryRgbOutput target;
    if (target.initialize(finalPath, 2, 1, 1) != SuperResNativeCode::Ok ||
        !target.prepare_window(0, 1)) {
        return false;
    }
    unsigned char* row = target.row(0, 0);
    if (row == nullptr) {
        return false;
    }
    std::copy(expected.begin(), expected.end(), row);
    SuperResStagedOutput staged(finalPath);
    const bool passed = target.finish_attempt(staged) == SuperResNativeCode::Ok &&
        read_bytes(staged.temporary_path()) == expected;
    staged.abort();
    std::remove(finalPath.c_str());
    return passed;
}

} // namespace

bool run_rgb_output_target_runtime_checks() {
    return memory_target_writes_and_stages_rgb_check() &&
        memory_target_maps_staging_failure_to_output_failed_check() &&
        output_target_keeps_callback_before_failed_publish_check() &&
        output_target_cancel_keeps_old_final_without_rename_check() &&
        streaming_target_flushes_windows_in_row_order_check() &&
        streaming_target_rejects_out_of_order_and_out_of_bounds_windows_check() &&
        streaming_target_preserves_zero_band_failure_code_check() &&
        streaming_target_finish_failure_is_reported_and_cleaned_check() &&
        existing_rgb8_quantization_is_preserved_check() &&
        production_streaming_target_publishes_decodable_png_check() &&
        production_streaming_target_late_callback_preserves_old_final_check() &&
        production_streaming_target_cancel_preserves_old_final_check() &&
        production_streaming_target_rename_failure_preserves_old_final_check() &&
        memory_target_stages_tight_cropped_bytes_check();
}
