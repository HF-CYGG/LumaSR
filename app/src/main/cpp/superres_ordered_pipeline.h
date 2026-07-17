#pragma once

#include <atomic>
#include <functional>
#include <thread>

enum class SuperResOrderedPipelineStepKind {
    Ok,
    Finished,
    Cancelled,
    InitializationFailed,
    Failed
};

struct SuperResOrderedPipelineStepResult {
    SuperResOrderedPipelineStepKind kind = SuperResOrderedPipelineStepKind::Ok;
    int errorCode = 0;

    static SuperResOrderedPipelineStepResult ok() noexcept;
    static SuperResOrderedPipelineStepResult finished() noexcept;
    static SuperResOrderedPipelineStepResult cancelled(int errorCode) noexcept;
    static SuperResOrderedPipelineStepResult initialization_failed(int errorCode) noexcept;
    static SuperResOrderedPipelineStepResult failed(int errorCode) noexcept;
};

struct SuperResVulkanPipelineAttemptResult {
    int resultCode = 0;
    bool initializationFailed = false;
    bool outputWritten = false;
};

int dispatch_superres_vulkan_pipeline(
    int initialMaxPipelineDepth,
    const std::function<int()>& runSerial,
    const std::function<SuperResVulkanPipelineAttemptResult()>& runOrderedDepthTwo
);

struct SuperResOrderedPipelineProducerControl {
    int desiredDepth = 1;
    bool shouldCancel = false;
    int cancellationErrorCode = 0;
};

class SuperResOrderedPipelineCancellationToken {
public:
    bool stop_requested() const noexcept;

private:
    explicit SuperResOrderedPipelineCancellationToken(const std::atomic<bool>* stopped) noexcept;

    const std::atomic<bool>* stopped_ = nullptr;

    friend struct SuperResOrderedPipelineCancellationTokenFactory;
};

struct SuperResOrderedPipelineTelemetry {
    int effectiveDepth = 1;
    int peakOccupiedSlots = 0;
    long long producerWaitMs = 0;
    long long consumerWaitMs = 0;
    long long overlapMs = 0;
};

struct SuperResOrderedPipelineRunResult {
    SuperResOrderedPipelineStepResult step;
    SuperResOrderedPipelineTelemetry telemetry;
};

struct SuperResOrderedPipelineOperations {
    int itemCount = 0;
    int maximumDepth = 1;
    std::function<SuperResOrderedPipelineProducerControl(int sequence)> pollProducerControl;
    std::function<SuperResOrderedPipelineStepResult(
        int slotIndex,
        int sequence,
        const SuperResOrderedPipelineCancellationToken& token
    )> produce;
    // Runs on the producer thread after GPU_READY is visible to the consumer. It is suitable for
    // producer-only throttling and must not mutate slot payload consumed by the CPU thread.
    std::function<void(int slotIndex, int sequence)> onProduced;
    std::function<SuperResOrderedPipelineStepResult(
        int slotIndex,
        int sequence,
        const SuperResOrderedPipelineCancellationToken& token
    )> consume;
    // Completion callbacks run on the producer thread after the slot is no longer used by the
    // consumer, and before that slot can be reused.
    std::function<void(int slotIndex, int sequence)> onConsumed;
    // Injectable launcher keeps thread-allocation failure observable without running GPU code.
    std::function<std::thread(std::function<void()>)> launchConsumer;
};

SuperResOrderedPipelineRunResult run_superres_ordered_pipeline(
    const SuperResOrderedPipelineOperations& operations
);
