#include "superres_output_finalizer.h"

#include <cstdio>
#include <fstream>

#if defined(_WIN32)
#include <io.h>
#else
#include <unistd.h>
#endif

namespace {

FILE* open_staged_file(const std::string& path) {
#if defined(_WIN32)
    FILE* file = nullptr;
    return ::fopen_s(&file, path.c_str(), "wb") == 0 ? file : nullptr;
#else
    return std::fopen(path.c_str(), "wb");
#endif
}

bool sync_staged_file(FILE* file) {
#if defined(_WIN32)
    return ::_commit(::_fileno(file)) == 0;
#else
    return ::fsync(::fileno(file)) == 0;
#endif
}

long long elapsed_milliseconds(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start
    ).count();
}

} // namespace

SuperResSafePublishTiming make_superres_safe_publish_timing(
    long long stagingMs,
    long long prePublishTotalMs
) {
    return SuperResSafePublishTiming{stagingMs, stagingMs, prePublishTotalMs};
}

SuperResStagedOutput::SuperResStagedOutput(std::string outputPath)
    : outputPath_(std::move(outputPath)), temporaryPath_(outputPath_ + ".part") {}

SuperResStagedOutput::~SuperResStagedOutput() {
    if (temporaryReady_) {
        std::remove(temporaryPath_.c_str());
    }
}

SuperResStageResult SuperResStagedOutput::stage_bytes(
    const unsigned char* bytes,
    size_t size
) {
    const auto start = std::chrono::steady_clock::now();
    if (outputPath_.empty() || bytes == nullptr || size == 0) {
        return SuperResStageResult{SuperResStageStatus::InvalidParams, elapsed_milliseconds(start)};
    }
    std::remove(temporaryPath_.c_str());
    FILE* file = open_staged_file(temporaryPath_);
    if (file == nullptr) {
        return SuperResStageResult{SuperResStageStatus::IoError, elapsed_milliseconds(start)};
    }
    bool succeeded = std::fwrite(bytes, 1, size, file) == size;
    succeeded = succeeded && std::fflush(file) == 0;
    succeeded = succeeded && sync_staged_file(file);
    succeeded = std::fclose(file) == 0 && succeeded;
    if (!succeeded) {
        std::remove(temporaryPath_.c_str());
        temporaryReady_ = false;
        return SuperResStageResult{SuperResStageStatus::IoError, elapsed_milliseconds(start)};
    }
    temporaryReady_ = true;
    return SuperResStageResult{SuperResStageStatus::Ok, elapsed_milliseconds(start)};
}

bool SuperResStagedOutput::adopt_finished_temporary() {
    if (outputPath_.empty() || temporaryPath_.empty()) {
        return false;
    }
    std::ifstream temporary(temporaryPath_, std::ios::binary);
    temporaryReady_ = temporary.good();
    return temporaryReady_;
}

SuperResTaskPublishOutcome SuperResStagedOutput::publish_or_cancel(
    SuperResTaskRegistry& registry,
    const std::string& taskId,
    const SuperResAtomicPublishOps& operations
) {
    if (!temporaryReady_ || !operations.renameFile || !operations.syncParentDirectory ||
        !operations.removeFile) {
        return SuperResTaskPublishOutcome::Failed;
    }
    return registry.publish_or_cancel(
        taskId,
        [&]() {
            const SuperResAtomicPublishStatus status =
                publish_atomic_file(temporaryPath_, outputPath_, operations);
            // On failure keep the guard armed so destruction retries cleanup even if the atomic
            // publisher could not remove a partially staged file. A committed rename leaves no
            // temporary path, so the retry is harmless for directory-sync failures.
            temporaryReady_ = status != SuperResAtomicPublishStatus::Ok;
            return status == SuperResAtomicPublishStatus::Ok;
        },
        [&]() { abort(operations); }
    );
}

void SuperResStagedOutput::abort(const SuperResAtomicPublishOps& operations) {
    if (temporaryReady_ && operations.removeFile) {
        temporaryReady_ = operations.removeFile(temporaryPath_.c_str()) != 0;
        return;
    }
    temporaryReady_ = false;
}
