#pragma once

#include "superres_atomic_publish.h"
#include "superres_task_registry.h"

#include <chrono>
#include <cstddef>
#include <string>
#include <utility>

enum class SuperResStageStatus {
    Ok,
    InvalidParams,
    IoError
};

struct SuperResStageResult {
    SuperResStageStatus status = SuperResStageStatus::InvalidParams;
    long long stagingMs = 0;
};

// saveMs/encodeMs cover complete temporary-file creation. totalMs ends immediately before the
// potentially throwing performance callback and atomic publication; neither field includes rename.
struct SuperResSafePublishTiming {
    long long saveMs = 0;
    long long encodeMs = 0;
    long long totalMs = 0;
};

struct SuperResFinalizationResult {
    SuperResTaskPublishOutcome outcome = SuperResTaskPublishOutcome::Failed;
    long long publishMs = 0;
};

SuperResSafePublishTiming make_superres_safe_publish_timing(
    long long stagingMs,
    long long prePublishTotalMs
);

class SuperResStagedOutput {
public:
    explicit SuperResStagedOutput(std::string outputPath);
    ~SuperResStagedOutput();

    SuperResStagedOutput(const SuperResStagedOutput&) = delete;
    SuperResStagedOutput& operator=(const SuperResStagedOutput&) = delete;

    SuperResStageResult stage_bytes(const unsigned char* bytes, size_t size);
    bool adopt_finished_temporary();
    SuperResTaskPublishOutcome publish_or_cancel(
        SuperResTaskRegistry& registry,
        const std::string& taskId,
        const SuperResAtomicPublishOps& operations = default_atomic_publish_ops()
    );
    void abort(const SuperResAtomicPublishOps& operations = default_atomic_publish_ops());

    const std::string& temporary_path() const { return temporaryPath_; }
    const std::string& output_path() const { return outputPath_; }

private:
    std::string outputPath_;
    std::string temporaryPath_;
    bool temporaryReady_ = false;
};

template <typename EmitPerformance>
SuperResFinalizationResult finalize_superres_output_after_performance(
    SuperResStagedOutput& output,
    SuperResTaskRegistry& registry,
    const std::string& taskId,
    EmitPerformance&& emitPerformance,
    const SuperResAtomicPublishOps& operations = default_atomic_publish_ops()
) {
    // No final-path operation may happen until every potentially throwing callback has returned.
    std::forward<EmitPerformance>(emitPerformance)();
    const auto publishStart = std::chrono::steady_clock::now();
    const SuperResTaskPublishOutcome outcome =
        output.publish_or_cancel(registry, taskId, operations);
    const long long publishMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - publishStart
    ).count();
    return SuperResFinalizationResult{outcome, publishMs};
}
