#include "superres_extreme_session_checks.h"

#include "superres_extreme_session.h"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <fstream>
#include <limits>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace {

struct RecordingOutput {
    std::vector<std::string> calls;
    std::vector<std::vector<unsigned char>> bands;
    std::string temporaryPath;
    size_t rowBytes = 0;
    bool abortCalled = false;
};

bool write_bytes(const std::string& path, const std::vector<unsigned char>& bytes) {
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output.good()) {
        return false;
    }
    output.write(
        reinterpret_cast<const char*>(bytes.data()),
        static_cast<std::streamsize>(bytes.size())
    );
    return output.good();
}

std::vector<unsigned char> read_bytes(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    return std::vector<unsigned char>(
        std::istreambuf_iterator<char>(input),
        std::istreambuf_iterator<char>()
    );
}

bool file_exists(const std::string& path) {
    return std::ifstream(path, std::ios::binary).good();
}

StreamingPngOutputOps recording_output_ops(
    const std::shared_ptr<RecordingOutput>& recording
) {
    return StreamingPngOutputOps{
        [recording](const std::string& outputPath, int width, int height) {
            recording->calls.push_back(
                "open:" + std::to_string(width) + "x" + std::to_string(height)
            );
            recording->temporaryPath = outputPath + ".part";
            recording->rowBytes = static_cast<size_t>(width) * 3u;
            return write_bytes(recording->temporaryPath, {0})
                ? StreamingPngStatus::Ok
                : StreamingPngStatus::IoError;
        },
        [recording](const unsigned char* rgb, int rowCount, size_t rowStride) {
            recording->calls.push_back("rows:" + std::to_string(rowCount));
            if (rgb == nullptr || rowCount <= 0 || rowStride < recording->rowBytes) {
                return StreamingPngStatus::InvalidParams;
            }
            std::vector<unsigned char> band;
            for (int row = 0; row < rowCount; ++row) {
                const unsigned char* begin = rgb + static_cast<size_t>(row) * rowStride;
                band.insert(band.end(), begin, begin + recording->rowBytes);
            }
            recording->bands.push_back(std::move(band));
            return StreamingPngStatus::Ok;
        },
        [recording]() {
            recording->calls.push_back("finish");
            return write_bytes(recording->temporaryPath, {0x89, 0x50, 0x4e, 0x47})
                ? StreamingPngStatus::Ok
                : StreamingPngStatus::IoError;
        },
        [recording]() {
            recording->abortCalled = true;
            std::remove(recording->temporaryPath.c_str());
        }
    };
}

struct SessionFixture {
    std::string taskId;
    std::string outputPath;
    std::shared_ptr<RecordingOutput> recording = std::make_shared<RecordingOutput>();
    SuperResTaskRegistry taskRegistry;
    int registerCount = 0;
    int completeCount = 0;
    bool cancelled = false;
    bool failPublish = false;

    explicit SessionFixture(std::string suffix)
        : taskId("extreme-" + suffix),
          outputPath("superres-extreme-" + suffix + ".png") {
        std::remove(outputPath.c_str());
        std::remove((outputPath + ".part").c_str());
    }

    ~SessionFixture() {
        std::remove(outputPath.c_str());
        std::remove((outputPath + ".part").c_str());
    }

    SuperResExtremeSessionDependencies dependencies() {
        SuperResExtremeSessionDependencies result;
        result.makeOutput = [recording = recording]() {
            return std::make_unique<StreamingPngOutput>(recording_output_ops(recording));
        };
        result.registerTask = [this](const std::string& value) {
            ++registerCount;
            return taskRegistry.register_task(value);
        };
        result.completeTask = [this](const std::string& value) {
            ++completeCount;
            taskRegistry.complete_task(value);
        };
        result.consumeCancelled = [this](const std::string&) {
            const bool value = cancelled;
            cancelled = false;
            return value;
        };
        result.publishOutput = [this](
            SuperResStagedOutput& staged,
            const std::string& value
        ) {
            const SuperResAtomicPublishOps operations{
                [this](const char* from, const char* to) {
                    return failPublish ? -1 : std::rename(from, to);
                },
                [](const std::string&) { return true; },
                [](const char* path) { return std::remove(path); }
            };
            return staged.publish_or_cancel(taskRegistry, value, operations);
        };
        return result;
    }
};

SuperResExtremeSessionConfig four_tile_config(const SessionFixture& fixture) {
    return SuperResExtremeSessionConfig{
        fixture.taskId,
        fixture.outputPath,
        4,
        3,
        2,
        {
            {0, 0, 0, 2, 2},
            {1, 2, 0, 2, 2},
            {2, 0, 2, 2, 1},
            {3, 2, 2, 2, 1}
        }
    };
}

SuperResNativeCode fill_tile(
    const SuperResExtremeTileConfig& tile,
    RgbOutputTarget& output,
    unsigned char value
) {
    if (output.initialize("unused", tile.outputWidth, tile.outputHeight, 1) !=
        SuperResNativeCode::Ok) {
        return output.failure_code();
    }
    for (int y = 0; y < tile.outputHeight; ++y) {
        if (!output.prepare_window(y, 1)) {
            return output.failure_code() == SuperResNativeCode::Ok
                ? SuperResNativeCode::TileOutputMismatch
                : output.failure_code();
        }
        unsigned char* row = output.row(y, 0);
        if (row == nullptr) {
            return SuperResNativeCode::TileOutputMismatch;
        }
        std::fill_n(row, static_cast<size_t>(tile.outputWidth) * 3u, value);
    }
    return SuperResNativeCode::Ok;
}

std::unique_ptr<SuperResExtremePngSession> create_session(
    SessionFixture& fixture,
    const SuperResExtremeSessionConfig& config,
    SuperResNativeCode& code
) {
    return SuperResExtremePngSession::create(config, fixture.dependencies(), code);
}

bool row_band_columns_and_band_switch_are_ordered_check() {
    SessionFixture fixture("band-order");
    SuperResNativeCode beginCode = SuperResNativeCode::InvalidParams;
    auto session = create_session(fixture, four_tile_config(fixture), beginCode);
    if (session == nullptr || beginCode != SuperResNativeCode::Ok) {
        return false;
    }
    for (int index = 0; index < 4; ++index) {
        const unsigned char value = static_cast<unsigned char>(index + 1);
        const SuperResNativeCode code = session->process_tile(
            index,
            2,
            index < 2 ? 2 : 1,
            [value](const SuperResExtremeTileConfig& tile, RgbOutputTarget& output) {
                return fill_tile(tile, output, value);
            }
        );
        if (code != SuperResNativeCode::Ok) {
            return false;
        }
    }
    if (session->finish() != SuperResNativeCode::Ok) {
        return false;
    }
    const std::vector<std::string> expectedCalls{
        "open:4x3", "rows:2", "rows:1", "finish"
    };
    const std::vector<std::vector<unsigned char>> expectedBands{
        {
            1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2,
            1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2
        },
        {3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4}
    };
    return fixture.recording->calls == expectedCalls &&
        fixture.recording->bands == expectedBands && fixture.registerCount == 1 &&
        fixture.completeCount == 1 && !file_exists(fixture.outputPath + ".part");
}

bool out_of_order_is_rejected_without_advancing_check() {
    SessionFixture fixture("out-of-order");
    SuperResNativeCode beginCode = SuperResNativeCode::InvalidParams;
    auto session = create_session(fixture, four_tile_config(fixture), beginCode);
    if (session == nullptr) {
        return false;
    }
    int runs = 0;
    const auto runner = [&runs](const SuperResExtremeTileConfig& tile, RgbOutputTarget& output) {
        ++runs;
        return fill_tile(tile, output, 7);
    };
    const SuperResNativeCode rejected = session->process_tile(1, 2, 2, runner);
    const SuperResNativeCode accepted = session->process_tile(0, 2, 2, runner);
    session->abort();
    return beginCode == SuperResNativeCode::Ok &&
        rejected == SuperResNativeCode::InvalidParams &&
        accepted == SuperResNativeCode::Ok && runs == 1 &&
        session->completed_tiles() == 1u;
}

bool failed_tile_does_not_advance_and_can_be_overwritten_check() {
    SessionFixture fixture("retry");
    const SuperResExtremeSessionConfig config{
        fixture.taskId,
        fixture.outputPath,
        2,
        1,
        1,
        {{0, 0, 0, 2, 1}}
    };
    SuperResNativeCode beginCode = SuperResNativeCode::InvalidParams;
    auto session = create_session(fixture, config, beginCode);
    if (session == nullptr) {
        return false;
    }
    const SuperResNativeCode failed = session->process_tile(
        0,
        2,
        1,
        [](const SuperResExtremeTileConfig& tile, RgbOutputTarget& output) {
            if (fill_tile(tile, output, 1) != SuperResNativeCode::Ok) {
                return SuperResNativeCode::TileOutputMismatch;
            }
            return SuperResNativeCode::VulkanFailed;
        }
    );
    const size_t cursorAfterFailure = session->completed_tiles();
    const SuperResNativeCode retried = session->process_tile(
        0,
        2,
        1,
        [](const SuperResExtremeTileConfig& tile, RgbOutputTarget& output) {
            return fill_tile(tile, output, 9);
        }
    );
    const SuperResNativeCode finished = session->finish();
    return beginCode == SuperResNativeCode::Ok && failed == SuperResNativeCode::VulkanFailed &&
        cursorAfterFailure == 0u && retried == SuperResNativeCode::Ok &&
        finished == SuperResNativeCode::Ok &&
        fixture.recording->bands == std::vector<std::vector<unsigned char>>{{9, 9, 9, 9, 9, 9}};
}

bool incomplete_finish_is_rejected_but_session_remains_usable_check() {
    SessionFixture fixture("incomplete");
    SuperResNativeCode beginCode = SuperResNativeCode::InvalidParams;
    auto session = create_session(fixture, four_tile_config(fixture), beginCode);
    if (session == nullptr || session->finish() != SuperResNativeCode::InvalidParams) {
        return false;
    }
    for (int index = 0; index < 4; ++index) {
        if (session->process_tile(
                index,
                2,
                index < 2 ? 2 : 1,
                [](const SuperResExtremeTileConfig& tile, RgbOutputTarget& output) {
                    return fill_tile(tile, output, 5);
                }
            ) != SuperResNativeCode::Ok) {
            return false;
        }
    }
    return session->finish() == SuperResNativeCode::Ok && fixture.completeCount == 1;
}

bool cancellation_abort_and_repeated_abort_are_idempotent_check() {
    SessionFixture fixture("cancel");
    SuperResNativeCode beginCode = SuperResNativeCode::InvalidParams;
    auto session = create_session(fixture, four_tile_config(fixture), beginCode);
    if (session == nullptr) {
        return false;
    }
    fixture.cancelled = true;
    int runs = 0;
    const SuperResNativeCode cancelled = session->process_tile(
        0,
        2,
        2,
        [&runs](const SuperResExtremeTileConfig&, RgbOutputTarget&) {
            ++runs;
            return SuperResNativeCode::Ok;
        }
    );
    session->abort();
    session->abort();
    return cancelled == SuperResNativeCode::Cancelled && runs == 0 &&
        session->completed_tiles() == 0u && fixture.registerCount == 1 &&
        fixture.completeCount == 1 && fixture.recording->abortCalled &&
        !file_exists(fixture.outputPath + ".part");
}

bool publish_failure_preserves_old_final_and_completes_once_check() {
    SessionFixture fixture("publish-failure");
    const std::vector<unsigned char> oldFinal{'o', 'l', 'd'};
    if (!write_bytes(fixture.outputPath, oldFinal)) {
        return false;
    }
    fixture.failPublish = true;
    const SuperResExtremeSessionConfig config{
        fixture.taskId,
        fixture.outputPath,
        1,
        1,
        1,
        {{0, 0, 0, 1, 1}}
    };
    SuperResNativeCode beginCode = SuperResNativeCode::InvalidParams;
    auto session = create_session(fixture, config, beginCode);
    if (session == nullptr || session->process_tile(
            0,
            1,
            1,
            [](const SuperResExtremeTileConfig& tile, RgbOutputTarget& output) {
                return fill_tile(tile, output, 8);
            }
        ) != SuperResNativeCode::Ok) {
        return false;
    }
    const SuperResNativeCode finishCode = session->finish();
    session->abort();
    return finishCode == SuperResNativeCode::OutputFailed &&
        read_bytes(fixture.outputPath) == oldFinal &&
        !file_exists(fixture.outputPath + ".part") && fixture.registerCount == 1 &&
        fixture.completeCount == 1;
}

bool invalid_geometry_and_checked_overflow_fail_before_registration_check() {
    SessionFixture fixture("overflow");
    const SuperResExtremeSessionConfig invalid{
        fixture.taskId,
        fixture.outputPath,
        std::numeric_limits<int>::max(),
        1,
        1,
        {{0, std::numeric_limits<int>::max() - 1, 0, 2, 1}}
    };
    SuperResNativeCode beginCode = SuperResNativeCode::Ok;
    auto session = create_session(fixture, invalid, beginCode);
    size_t flatLength = 99u;
    const bool overflowRejected = !checked_extreme_tile_config_array_length(
        std::numeric_limits<size_t>::max(),
        5u,
        flatLength
    );
    return session == nullptr && beginCode == SuperResNativeCode::InvalidParams &&
        fixture.registerCount == 0 && fixture.completeCount == 0 && overflowRejected &&
        flatLength == 0u;
}

bool handle_registry_is_thread_safe_and_never_returns_zero_check() {
    SessionFixture fixture("handles");
    const SuperResExtremeSessionConfig config{
        fixture.taskId,
        fixture.outputPath,
        1,
        1,
        1,
        {{0, 0, 0, 1, 1}}
    };
    SuperResNativeCode beginCode = SuperResNativeCode::InvalidParams;
    std::shared_ptr<SuperResExtremePngSession> session =
        create_session(fixture, config, beginCode);
    if (session == nullptr) {
        return false;
    }
    SuperResExtremeSessionHandleRegistry registry;
    const uint64_t handle = registry.insert(session);
    if (handle == 0u || registry.find(handle) != session) {
        return false;
    }
    std::atomic<int> finds{0};
    std::thread first([&]() { if (registry.find(handle) != nullptr) ++finds; });
    std::thread second([&]() { if (registry.find(handle) != nullptr) ++finds; });
    first.join();
    second.join();
    const auto removed = registry.remove(handle);
    session->abort();
    return finds == 2 && removed == session && registry.find(handle) == nullptr &&
        registry.remove(handle) == nullptr;
}

} // namespace

bool run_superres_extreme_session_runtime_checks() {
    return row_band_columns_and_band_switch_are_ordered_check() &&
        out_of_order_is_rejected_without_advancing_check() &&
        failed_tile_does_not_advance_and_can_be_overwritten_check() &&
        incomplete_finish_is_rejected_but_session_remains_usable_check() &&
        cancellation_abort_and_repeated_abort_are_idempotent_check() &&
        publish_failure_preserves_old_final_and_completes_once_check() &&
        invalid_geometry_and_checked_overflow_fail_before_registration_check() &&
        handle_registry_is_thread_safe_and_never_returns_zero_check();
}
