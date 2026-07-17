#include "superres_host_checks.h"
#include "superres_rgb_output_target_checks.h"

#include "superres_jni_safety.h"
#include "superres_output_finalizer.h"
#include "superres_process_internal.h"
#include "superres_stbi_decode.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdint>
#include <fstream>
#include <functional>
#include <new>
#include <stdexcept>
#include <string>
#include <thread>

namespace {
struct FakeJniThrowable {
    bool outOfMemory = false;
};

struct FakeJniClass {};

struct FakeUtfEnvironment {
    int getCount = 0;
    int releaseCount = 0;
    int clearCount = 0;
    int deletedThrowableCount = 0;
    int deletedClassCount = 0;
    bool pending = false;
    bool pendingOutOfMemory = false;
    bool failUtf = false;
    bool failUtfWithOutOfMemory = false;
    bool failFindClass = false;
    bool invalidJniCallWhilePending = false;
    FakeJniThrowable throwable;
    FakeJniClass outOfMemoryClass;

    bool ExceptionCheck() const { return pending; }

    FakeJniThrowable* ExceptionOccurred() {
        throwable.outOfMemory = pendingOutOfMemory;
        return pending ? &throwable : nullptr;
    }

    void ExceptionClear() {
        pending = false;
        ++clearCount;
    }

    FakeJniClass* FindClass(const char*) {
        invalidJniCallWhilePending = invalidJniCallWhilePending || pending;
        if (failFindClass) {
            setPending(true);
            return nullptr;
        }
        return &outOfMemoryClass;
    }

    bool IsInstanceOf(FakeJniThrowable* value, FakeJniClass*) {
        invalidJniCallWhilePending = invalidJniCallWhilePending || pending;
        return value != nullptr && value->outOfMemory;
    }

    void DeleteLocalRef(FakeJniThrowable*) {
        invalidJniCallWhilePending = invalidJniCallWhilePending || pending;
        ++deletedThrowableCount;
    }

    void DeleteLocalRef(FakeJniClass*) {
        invalidJniCallWhilePending = invalidJniCallWhilePending || pending;
        ++deletedClassCount;
    }

    void setPending(bool outOfMemory) {
        pending = true;
        pendingOutOfMemory = outOfMemory;
    }

    const char* GetStringUTFChars(const char* value, void*) {
        ++getCount;
        if (failUtf) {
            setPending(failUtfWithOutOfMemory);
            return nullptr;
        }
        return value;
    }

    void ReleaseStringUTFChars(const char*, const char*) { ++releaseCount; }
};

[[noreturn]] std::string throw_string_bad_alloc(const char*) { throw std::bad_alloc{}; }

[[noreturn]] int throw_boundary_bad_alloc() { throw std::bad_alloc{}; }

[[noreturn]] int throw_boundary_runtime_error() {
    throw std::runtime_error("callback construction failed");
}

bool jni_boundaries_and_utf_release_check() {
    FakeUtfEnvironment environment;
    const int parameterCode = run_superres_process_jni_boundary(&environment, [&environment]() {
        (void)superres_to_string_with(&environment, "task", throw_string_bad_alloc);
        return static_cast<int>(SuperResNativeCode::Ok);
    });
    const int callbackCode =
        run_superres_bitmap_jni_boundary(&environment, throw_boundary_bad_alloc);
    const int stableOtherCode =
        run_superres_bitmap_jni_boundary(&environment, throw_boundary_runtime_error);
    return parameterCode == static_cast<int>(SuperResNativeCode::OutOfMemory) &&
           callbackCode == static_cast<int>(SuperResNativeCode::OutOfMemory) &&
           stableOtherCode == static_cast<int>(SuperResNativeCode::InvalidParams) &&
           environment.getCount == 1 && environment.releaseCount == 1;
}

bool jni_pending_exception_boundary_check() {
    FakeUtfEnvironment environment;
    int bodyCount = 0;

    environment.setPending(true);
    const int entryOutOfMemoryCode = run_superres_process_jni_boundary(&environment, [&]() {
        ++bodyCount;
        return static_cast<int>(SuperResNativeCode::Ok);
    });
    const bool entryOutOfMemoryCleared = !environment.ExceptionCheck() && bodyCount == 0;

    environment.setPending(false);
    const int entryOtherCode = run_superres_bitmap_jni_boundary(&environment, [&]() {
        ++bodyCount;
        return static_cast<int>(SuperResNativeCode::Ok);
    });
    const bool entryOtherCleared = !environment.ExceptionCheck() && bodyCount == 0;

    const int exitOtherCode = run_superres_process_jni_boundary(&environment, [&]() {
        ++bodyCount;
        environment.setPending(false);
        return static_cast<int>(SuperResNativeCode::Ok);
    });
    const bool exitOtherCleared = !environment.ExceptionCheck() && bodyCount == 1;

    const int exitOutOfMemoryCode = run_superres_bitmap_jni_boundary(&environment, [&]() {
        ++bodyCount;
        environment.setPending(true);
        return static_cast<int>(SuperResNativeCode::Ok);
    });
    const bool exitOutOfMemoryCleared = !environment.ExceptionCheck() && bodyCount == 2;

    return entryOutOfMemoryCode == static_cast<int>(SuperResNativeCode::OutOfMemory) &&
           entryOtherCode == static_cast<int>(SuperResNativeCode::InvalidParams) &&
           exitOtherCode == static_cast<int>(SuperResNativeCode::InvalidParams) &&
           exitOutOfMemoryCode == static_cast<int>(SuperResNativeCode::OutOfMemory) &&
           entryOutOfMemoryCleared && entryOtherCleared && exitOtherCleared &&
           exitOutOfMemoryCleared && environment.clearCount == 4 &&
           environment.deletedThrowableCount == 4 && environment.deletedClassCount == 4 &&
           !environment.invalidJniCallWhilePending;
}

bool jni_out_of_memory_classification_failure_check() {
    FakeUtfEnvironment environment;
    environment.failFindClass = true;
    environment.setPending(false);
    int bodyCount = 0;
    const int code = run_superres_process_jni_boundary(&environment, [&]() {
        ++bodyCount;
        return static_cast<int>(SuperResNativeCode::Ok);
    });
    return code == static_cast<int>(SuperResNativeCode::OutOfMemory) && bodyCount == 0 &&
           !environment.ExceptionCheck() && environment.clearCount == 2 &&
           environment.deletedThrowableCount == 1 && environment.deletedClassCount == 0 &&
           !environment.invalidJniCallWhilePending;
}

bool utf_pending_exception_check() {
    FakeUtfEnvironment outOfMemoryEnvironment;
    outOfMemoryEnvironment.failUtf = true;
    outOfMemoryEnvironment.failUtfWithOutOfMemory = true;
    int outOfMemoryFactoryCount = 0;
    const int outOfMemoryCode = run_superres_process_jni_boundary(
        &outOfMemoryEnvironment,
        [&]() {
            (void)superres_to_string_with(
                &outOfMemoryEnvironment,
                "task",
                [&outOfMemoryFactoryCount](const char* chars) {
                    ++outOfMemoryFactoryCount;
                    return std::string(chars);
                });
            return static_cast<int>(SuperResNativeCode::Ok);
        });

    FakeUtfEnvironment otherEnvironment;
    otherEnvironment.failUtf = true;
    int otherFactoryCount = 0;
    const int otherCode = run_superres_process_jni_boundary(
        &otherEnvironment,
        [&]() {
            (void)superres_to_string_with(
                &otherEnvironment,
                "task",
                [&otherFactoryCount](const char* chars) {
                    ++otherFactoryCount;
                    return std::string(chars);
                });
            return static_cast<int>(SuperResNativeCode::Ok);
        });
    return outOfMemoryCode == static_cast<int>(SuperResNativeCode::OutOfMemory) &&
           !outOfMemoryEnvironment.ExceptionCheck() && outOfMemoryEnvironment.clearCount == 1 &&
           outOfMemoryEnvironment.getCount == 1 && outOfMemoryEnvironment.releaseCount == 0 &&
           outOfMemoryFactoryCount == 0 &&
           !outOfMemoryEnvironment.invalidJniCallWhilePending &&
           otherCode == static_cast<int>(SuperResNativeCode::InvalidParams) &&
           !otherEnvironment.ExceptionCheck() && otherEnvironment.clearCount == 1 &&
           otherEnvironment.getCount == 1 && otherEnvironment.releaseCount == 0 &&
           otherFactoryCount == 0 && !otherEnvironment.invalidJniCallWhilePending;
}

SuperResNativeParams valid_params(const char* taskId) {
    SuperResNativeParams params{};
    params.taskId = taskId;
    params.outputPath = "output.png";
    params.engineType = 0;
    params.modelDir = "models";
    params.scale = 2;
    params.tileSize = 64;
    params.accelerationMode = 0;
    params.outputMode = 0;
    return params;
}

SuperResRgbImage one_pixel_image(unsigned char value) {
    return SuperResRgbImage(
        1,
        1,
        3,
        3,
        SuperResRgbPixels(new unsigned char[3]{value, value, value}, SuperResRgbDeleter{}));
}

bool process_internal_loads_once_and_reuses_pixels_check() {
    int loadCount = 0;
    int registerCount = 0;
    int completeCount = 0;
    int runCount = 0;
    int fallbackEventCount = 0;
    const unsigned char* addresses[2] = {};
    bool backends[2] = {};

    SuperResProcessInternalDependencies dependencies;
    dependencies.loadImage = [&loadCount]() {
        ++loadCount;
        return one_pixel_image(42);
    };
    dependencies.runImage =
        [&runCount, &addresses, &backends](const SuperResNativeParams&,
                                           const SuperResProgressCallback&,
                                           const SuperResRuntimeControlCallback&,
                                           bool useVulkan,
                                           const SuperResRgbImage& image,
                                           long long) {
            addresses[runCount] = image.data();
            backends[runCount] = useVulkan;
            ++runCount;
            return useVulkan ? SuperResNativeCode::VulkanFailed : SuperResNativeCode::Ok;
        };
    dependencies.registerTask = [&registerCount](const std::string&) {
        ++registerCount;
        return true;
    };
    dependencies.completeTask = [&completeCount](const std::string&) { ++completeCount; };
    dependencies.inputExists = [](const std::string&) { return true; };
    dependencies.onEvent = [&fallbackEventCount](SuperResProcessInternalEvent event,
                                                 SuperResNativeCode) {
        if (event == SuperResProcessInternalEvent::AutoCpuFallback) {
            ++fallbackEventCount;
        }
    };

    const SuperResNativeCode code =
        process_superres_internal(valid_params("owned-fallback"), {}, {}, false, dependencies);
    return code == SuperResNativeCode::Ok && loadCount == 1 && runCount == 2 &&
           registerCount == 1 && completeCount == 1 && fallbackEventCount == 1 && backends[0] &&
           !backends[1] && addresses[0] != nullptr && addresses[0] == addresses[1];
}

bool process_jni_boundary_covers_register_and_emit_check() {
    FakeUtfEnvironment environment;
    int completionCount = 0;
    int runCount = 0;
    int runCompletedCount = 0;
    int progressCount = 0;
    SuperResProcessInternalDependencies dependencies;
    dependencies.loadImage = []() { return one_pixel_image(7); };
    dependencies.runImage = [](const SuperResNativeParams&,
                               const SuperResProgressCallback&,
                               const SuperResRuntimeControlCallback&,
                               bool,
                               const SuperResRgbImage&,
                               long long) { return SuperResNativeCode::Ok; };
    dependencies.registerTask = [](const std::string&) -> bool { throw std::bad_alloc{}; };
    dependencies.completeTask = [&completionCount](const std::string&) { ++completionCount; };
    dependencies.inputExists = [](const std::string&) { return true; };
    dependencies.onEvent = [](SuperResProcessInternalEvent, SuperResNativeCode) {};

    const int registerCode = run_superres_process_jni_boundary(&environment, [&dependencies]() {
        return static_cast<int>(
            process_superres_internal(valid_params("register-throw"), {}, {}, false, dependencies));
    });

    dependencies.registerTask = [](const std::string&) { return true; };
    const int emitCode = run_superres_process_jni_boundary(&environment, [&dependencies]() {
        const SuperResProgressCallback throwingProgress = [](SuperResNativeStage,
                                                             int,
                                                             int,
                                                             float,
                                                             const std::string&,
                                                             const SuperResNativePerformance&) {
            throw std::runtime_error("progress failed");
        };
        return static_cast<int>(process_superres_internal(
            valid_params("emit-throw"), throwingProgress, {}, false, dependencies));
    });

    dependencies.runImage =
        [&runCount, &runCompletedCount](const SuperResNativeParams&,
                                        const SuperResProgressCallback& onProgress,
                                        const SuperResRuntimeControlCallback&,
                                        bool,
                                        const SuperResRgbImage&,
                                        long long) {
        ++runCount;
        onProgress(SuperResNativeStage::ProcessingTile,
                   1,
                   1,
                   0.5f,
                   "Processing",
                   SuperResNativePerformance{});
        ++runCompletedCount;
        return SuperResNativeCode::Ok;
    };
    const int lateEmitCode = run_superres_process_jni_boundary(&environment, [&]() {
        const SuperResProgressCallback pendingProgress = [&](SuperResNativeStage,
                                                             int,
                                                             int,
                                                             float,
                                                             const std::string&,
                                                             const SuperResNativePerformance&) {
            ++progressCount;
            if (progressCount == 3) {
                environment.setPending(false);
                throw_if_superres_jni_exception_pending(&environment);
            }
        };
        return static_cast<int>(process_superres_internal(
            valid_params("late-emit-throw"), pendingProgress, {}, false, dependencies));
    });
    return registerCode == static_cast<int>(SuperResNativeCode::OutOfMemory) &&
           emitCode == static_cast<int>(SuperResNativeCode::InvalidParams) &&
           lateEmitCode == static_cast<int>(SuperResNativeCode::InvalidParams) && runCount == 1 &&
           runCompletedCount == 0 && progressCount == 3 && completionCount == 2 &&
           !environment.ExceptionCheck();
}

bool file_exists(const std::string& path) {
    return std::ifstream(path, std::ios::binary).good();
}

std::string read_file(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>());
}

bool stage_text(SuperResStagedOutput& output, const std::string& value) {
    const SuperResStageResult result = output.stage_bytes(
        reinterpret_cast<const unsigned char*>(value.data()),
        value.size()
    );
    return result.status == SuperResStageStatus::Ok && result.stagingMs >= 0;
}

SuperResAtomicPublishOps counting_publish_ops(int& renameCount, bool renameSucceeds) {
    return SuperResAtomicPublishOps{
        [&renameCount, renameSucceeds](const char* from, const char* to) {
            ++renameCount;
            return renameSucceeds ? std::rename(from, to) : -1;
        },
        [](const std::string&) { return true; },
        [](const char* path) { return std::remove(path); }
    };
}

bool successful_production_finalization_check() {
    const std::string finalPath = "superres-host-finalize-success.out";
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    SuperResTaskRegistry registry;
    if (!registry.register_task("finalize-success")) return false;
    SuperResStagedOutput output(finalPath);
    if (output.temporary_path() != finalPath + ".part" || !stage_text(output, "new-final") ||
        read_file(output.temporary_path()) != "new-final") {
        return false;
    }
    const SuperResFinalizationResult result = finalize_superres_output_after_performance(
        output,
        registry,
        "finalize-success",
        []() {}
    );
    registry.complete_task("finalize-success");
    const bool passed = result.outcome == SuperResTaskPublishOutcome::Published &&
        result.publishMs >= 0 && !file_exists(output.temporary_path()) &&
        read_file(finalPath) == "new-final";
    std::remove(finalPath.c_str());
    return passed;
}

bool late_performance_emit_precedes_final_publish_check() {
    const std::string finalPath = "superres-host-finalize-late-emit.out";
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    FakeUtfEnvironment environment;
    SuperResTaskRegistry registry;
    if (!registry.register_task("late-emit")) return false;
    int renameCount = 0;
    bool stagedBytesVisibleDuringEmit = false;
    const SuperResAtomicPublishOps operations = counting_publish_ops(renameCount, true);
    const int code = run_superres_process_jni_boundary(&environment, [&]() {
        SuperResStagedOutput output(finalPath);
        if (!stage_text(output, "complete")) {
            return static_cast<int>(SuperResNativeCode::OutputFailed);
        }
        const SuperResFinalizationResult result = finalize_superres_output_after_performance(
            output,
            registry,
            "late-emit",
            [&]() {
                stagedBytesVisibleDuringEmit = read_file(output.temporary_path()) == "complete";
                environment.setPending(false);
                throw_if_superres_jni_exception_pending(&environment);
            },
            operations
        );
        return result.outcome == SuperResTaskPublishOutcome::Published
            ? static_cast<int>(SuperResNativeCode::Ok)
            : static_cast<int>(SuperResNativeCode::OutputFailed);
    });
    registry.complete_task("late-emit");
    const bool passed = code == static_cast<int>(SuperResNativeCode::InvalidParams) &&
        stagedBytesVisibleDuringEmit && renameCount == 0 && !file_exists(finalPath + ".part") &&
        !file_exists(finalPath) && !environment.ExceptionCheck();
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    return passed;
}

bool cancelled_production_finalization_check() {
    const std::string finalPath = "superres-host-finalize-cancel.out";
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    SuperResTaskRegistry registry;
    if (!registry.register_task("finalize-cancel")) return false;
    SuperResStagedOutput output(finalPath);
    if (!stage_text(output, "cancelled")) return false;
    registry.cancel("finalize-cancel");
    int renameCount = 0;
    const SuperResFinalizationResult result = finalize_superres_output_after_performance(
        output,
        registry,
        "finalize-cancel",
        []() {},
        counting_publish_ops(renameCount, true)
    );
    registry.complete_task("finalize-cancel");
    const bool passed = result.outcome == SuperResTaskPublishOutcome::Cancelled &&
        renameCount == 0 && !file_exists(output.temporary_path()) && !file_exists(finalPath);
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    return passed;
}

bool publisher_failure_preserves_old_final_check() {
    const std::string finalPath = "superres-host-finalize-failure.out";
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    std::ofstream oldFinal(finalPath, std::ios::binary | std::ios::trunc);
    oldFinal << "old-final";
    oldFinal.close();
    if (!oldFinal.good()) return false;
    SuperResTaskRegistry registry;
    if (!registry.register_task("finalize-failure")) return false;
    SuperResStagedOutput output(finalPath);
    if (!stage_text(output, "replacement")) return false;
    int renameCount = 0;
    const SuperResFinalizationResult result = finalize_superres_output_after_performance(
        output,
        registry,
        "finalize-failure",
        []() {},
        counting_publish_ops(renameCount, false)
    );
    registry.complete_task("finalize-failure");
    const bool passed = result.outcome == SuperResTaskPublishOutcome::Failed &&
        renameCount == 1 && !file_exists(output.temporary_path()) &&
        read_file(finalPath) == "old-final";
    std::remove((finalPath + ".part").c_str());
    std::remove(finalPath.c_str());
    return passed;
}

bool safe_pre_publish_timing_contract_check() {
    const SuperResSafePublishTiming timing = make_superres_safe_publish_timing(37, 91);
    return timing.saveMs == 37 && timing.encodeMs == 37 && timing.totalMs == 91;
}

std::atomic<bool> oomLoadEntered{false};
std::atomic<int> fakeReleaseCount{0};
std::atomic<const char*> sharedFailureReason{"decode"};

unsigned char* fake_stbi_load(const char* path, int* width, int* height, int* channels, int) {
    const std::string value = path == nullptr ? "" : path;
    if (value == "ok") {
        *width = 1;
        *height = 1;
        *channels = 3;
        return new unsigned char[3]{3, 2, 1};
    }
    if (value == "oom") {
        sharedFailureReason.store("outofmem", std::memory_order_release);
        oomLoadEntered.store(true, std::memory_order_release);
        std::this_thread::sleep_for(std::chrono::milliseconds(30));
        return nullptr;
    }
    sharedFailureReason.store("decode", std::memory_order_release);
    return nullptr;
}

const char* fake_stbi_failure_reason() {
    return sharedFailureReason.load(std::memory_order_acquire);
}

void fake_stbi_release(unsigned char* pixels) noexcept {
    ++fakeReleaseCount;
    delete[] pixels;
}

SuperResStbiApi fake_stbi_api() {
    return SuperResStbiApi{fake_stbi_load, fake_stbi_failure_reason, fake_stbi_release};
}

bool stbi_decode_serializes_failure_classification_check() {
    oomLoadEntered.store(false, std::memory_order_relaxed);
    SuperResRgbDecodeStatus oomStatus = SuperResRgbDecodeStatus::DecodeFailed;
    SuperResRgbDecodeStatus decodeStatus = SuperResRgbDecodeStatus::OutOfMemory;
    std::thread oomThread(
        [&oomStatus]() { oomStatus = decode_superres_rgb_image("oom", fake_stbi_api()).status; });
    while (!oomLoadEntered.load(std::memory_order_acquire)) {
        std::this_thread::yield();
    }
    std::thread decodeThread([&decodeStatus]() {
        decodeStatus = decode_superres_rgb_image("decode", fake_stbi_api()).status;
    });
    oomThread.join();
    decodeThread.join();
    return oomStatus == SuperResRgbDecodeStatus::OutOfMemory &&
           decodeStatus == SuperResRgbDecodeStatus::DecodeFailed;
}

bool stbi_adopts_and_releases_pixels_check() {
    fakeReleaseCount.store(0, std::memory_order_relaxed);
    const unsigned char* adoptedAddress = nullptr;
    {
        SuperResRgbDecodeResult result = decode_superres_rgb_image("ok", fake_stbi_api());
        adoptedAddress = result.image.data();
        if (result.status != SuperResRgbDecodeStatus::Ok || !result.image.valid() ||
            adoptedAddress == nullptr || fakeReleaseCount.load(std::memory_order_relaxed) != 0) {
            return false;
        }
    }
    return adoptedAddress != nullptr && fakeReleaseCount.load(std::memory_order_relaxed) == 1;
}

struct FakeUnlockEnvironment {
    int unlockCount = 0;
};

int fake_unlock(FakeUnlockEnvironment* environment, int) noexcept {
    ++environment->unlockCount;
    return 0;
}

bool bitmap_unlock_guard_check() {
    FakeUnlockEnvironment environment;
    { SuperResBitmapUnlockGuard guard(&environment, 1, fake_unlock); }
    {
        SuperResBitmapUnlockGuard guard(&environment, 2, fake_unlock);
        if (guard.unlock() != 0 || guard.unlock() != 0)
            return false;
    }
    return environment.unlockCount == 2;
}
} // namespace

bool run_superres_host_runtime_checks() {
    return run_rgb_output_target_runtime_checks() &&
           jni_boundaries_and_utf_release_check() &&
           jni_pending_exception_boundary_check() &&
           jni_out_of_memory_classification_failure_check() && utf_pending_exception_check() &&
           process_internal_loads_once_and_reuses_pixels_check() &&
           process_jni_boundary_covers_register_and_emit_check() &&
           successful_production_finalization_check() &&
           late_performance_emit_precedes_final_publish_check() &&
           cancelled_production_finalization_check() &&
           publisher_failure_preserves_old_final_check() &&
           safe_pre_publish_timing_contract_check() &&
           stbi_decode_serializes_failure_classification_check() &&
           stbi_adopts_and_releases_pixels_check() && bitmap_unlock_guard_check();
}
