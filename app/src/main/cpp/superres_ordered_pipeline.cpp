#include "superres_ordered_pipeline.h"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

SuperResOrderedPipelineStepResult SuperResOrderedPipelineStepResult::ok() noexcept {
    return SuperResOrderedPipelineStepResult{SuperResOrderedPipelineStepKind::Ok, 0};
}

SuperResOrderedPipelineStepResult SuperResOrderedPipelineStepResult::finished() noexcept {
    return SuperResOrderedPipelineStepResult{SuperResOrderedPipelineStepKind::Finished, 0};
}

SuperResOrderedPipelineStepResult SuperResOrderedPipelineStepResult::cancelled(
    int errorCode
) noexcept {
    return SuperResOrderedPipelineStepResult{
        SuperResOrderedPipelineStepKind::Cancelled,
        errorCode
    };
}

SuperResOrderedPipelineStepResult SuperResOrderedPipelineStepResult::initialization_failed(
    int errorCode
) noexcept {
    return SuperResOrderedPipelineStepResult{
        SuperResOrderedPipelineStepKind::InitializationFailed,
        errorCode
    };
}

SuperResOrderedPipelineStepResult SuperResOrderedPipelineStepResult::failed(
    int errorCode
) noexcept {
    return SuperResOrderedPipelineStepResult{SuperResOrderedPipelineStepKind::Failed, errorCode};
}

struct SuperResOrderedPipelineCancellationTokenFactory {
    static SuperResOrderedPipelineCancellationToken make(const std::atomic<bool>* stopped) {
        return SuperResOrderedPipelineCancellationToken(stopped);
    }
};

SuperResOrderedPipelineCancellationToken::SuperResOrderedPipelineCancellationToken(
    const std::atomic<bool>* stopped
) noexcept : stopped_(stopped) {}

bool SuperResOrderedPipelineCancellationToken::stop_requested() const noexcept {
    return stopped_ != nullptr && stopped_->load(std::memory_order_acquire);
}

int dispatch_superres_vulkan_pipeline(
    int initialMaxPipelineDepth,
    const std::function<int()>& runSerial,
    const std::function<SuperResVulkanPipelineAttemptResult()>& runOrderedDepthTwo
) {
    if (initialMaxPipelineDepth != 2) {
        return runSerial();
    }
    const SuperResVulkanPipelineAttemptResult ordered = runOrderedDepthTwo();
    if (ordered.initializationFailed && !ordered.outputWritten) {
        return runSerial();
    }
    return ordered.resultCode;
}

namespace {
using Clock = std::chrono::steady_clock;
constexpr auto kProducerPollInterval = std::chrono::milliseconds(5);
constexpr int kCoordinatorFailureCode = -1;

enum class SlotState {
    Free,
    GpuReady,
    CpuBusy
};

struct Slot {
    SlotState state = SlotState::Free;
    int sequence = -1;
    bool producerReserved = false;
    bool completionPending = false;
};

struct SharedState {
    explicit SharedState(int slotCount) : slots(static_cast<size_t>(slotCount)) {}

    std::mutex mutex;
    std::condition_variable changed;
    std::vector<Slot> slots;
    std::atomic<bool> stopLatch{false};
    bool stopping = false;
    bool producerDone = false;
    bool producerActive = false;
    bool consumerActive = false;
    int nextConsumerSequence = 0;
    SuperResOrderedPipelineStepResult terminal = SuperResOrderedPipelineStepResult::ok();
    SuperResOrderedPipelineTelemetry telemetry;
    Clock::time_point lastActivityChange = Clock::now();
    Clock::duration producerWait{};
    Clock::duration consumerWait{};
    Clock::duration overlap{};
};

int occupied_slot_count(const SharedState& shared) {
    int occupied = 0;
    for (const Slot& slot : shared.slots) {
        if (slot.producerReserved || slot.state != SlotState::Free || slot.completionPending) {
            ++occupied;
        }
    }
    return occupied;
}

int find_reusable_slot(const SharedState& shared) {
    for (size_t index = 0; index < shared.slots.size(); ++index) {
        const Slot& slot = shared.slots[index];
        if (slot.state == SlotState::Free && !slot.producerReserved &&
            !slot.completionPending) {
            return static_cast<int>(index);
        }
    }
    return -1;
}

int find_ready_slot(const SharedState& shared, int sequence) {
    for (size_t index = 0; index < shared.slots.size(); ++index) {
        const Slot& slot = shared.slots[index];
        if (slot.state == SlotState::GpuReady && slot.sequence == sequence) {
            return static_cast<int>(index);
        }
    }
    return -1;
}

int find_pending_completion(const SharedState& shared) {
    int selected = -1;
    int selectedSequence = 0;
    for (size_t index = 0; index < shared.slots.size(); ++index) {
        const Slot& slot = shared.slots[index];
        if (!slot.completionPending) {
            continue;
        }
        if (selected < 0 || slot.sequence < selectedSequence) {
            selected = static_cast<int>(index);
            selectedSequence = slot.sequence;
        }
    }
    return selected;
}

void account_activity_locked(SharedState& shared, Clock::time_point now) {
    if (shared.producerActive && shared.consumerActive) {
        shared.overlap += now - shared.lastActivityChange;
    }
    shared.lastActivityChange = now;
}

void set_producer_active_locked(SharedState& shared, bool active) {
    const Clock::time_point now = Clock::now();
    account_activity_locked(shared, now);
    shared.producerActive = active;
}

void set_consumer_active_locked(SharedState& shared, bool active) {
    const Clock::time_point now = Clock::now();
    account_activity_locked(shared, now);
    shared.consumerActive = active;
}

void request_stop_locked(
    SharedState& shared,
    const SuperResOrderedPipelineStepResult& result
) {
    shared.stopLatch.store(true, std::memory_order_release);
    if (!shared.stopping) {
        shared.stopping = true;
        shared.terminal = result;
    }
    shared.changed.notify_all();
}

SuperResOrderedPipelineStepResult safe_consume(
    const SuperResOrderedPipelineOperations& operations,
    int slotIndex,
    int sequence,
    const SuperResOrderedPipelineCancellationToken& token
) noexcept {
    try {
        return operations.consume(slotIndex, sequence, token);
    } catch (...) {
        return SuperResOrderedPipelineStepResult::failed(kCoordinatorFailureCode);
    }
}

void run_consumer(
    const SuperResOrderedPipelineOperations& operations,
    SharedState& shared,
    const SuperResOrderedPipelineCancellationToken& token
) noexcept {
    while (true) {
        int slotIndex = -1;
        int sequence = -1;
        {
            std::unique_lock<std::mutex> lock(shared.mutex);
            while (!shared.stopping) {
                slotIndex = find_ready_slot(shared, shared.nextConsumerSequence);
                if (slotIndex >= 0) {
                    break;
                }
                if (shared.producerDone) {
                    return;
                }
                const Clock::time_point waitStart = Clock::now();
                shared.changed.wait(lock);
                shared.consumerWait += Clock::now() - waitStart;
            }
            if (shared.stopping) {
                return;
            }
            Slot& slot = shared.slots[static_cast<size_t>(slotIndex)];
            sequence = slot.sequence;
            slot.state = SlotState::CpuBusy;
            set_consumer_active_locked(shared, true);
        }

        const SuperResOrderedPipelineStepResult result = safe_consume(
            operations,
            slotIndex,
            sequence,
            token
        );

        {
            std::lock_guard<std::mutex> lock(shared.mutex);
            set_consumer_active_locked(shared, false);
            Slot& slot = shared.slots[static_cast<size_t>(slotIndex)];
            slot.state = SlotState::Free;
            if (result.kind == SuperResOrderedPipelineStepKind::Ok && !shared.stopping &&
                !token.stop_requested()) {
                slot.completionPending = true;
                ++shared.nextConsumerSequence;
            } else {
                slot.sequence = -1;
                request_stop_locked(
                    shared,
                    result.kind == SuperResOrderedPipelineStepKind::Ok
                        ? SuperResOrderedPipelineStepResult::cancelled(0)
                        : result
                );
            }
            shared.changed.notify_all();
        }
    }
}

bool acknowledge_completions_on_producer(
    const SuperResOrderedPipelineOperations& operations,
    SharedState& shared
) {
    while (true) {
        int slotIndex = -1;
        int sequence = -1;
        {
            std::lock_guard<std::mutex> lock(shared.mutex);
            if (shared.stopping) {
                return false;
            }
            slotIndex = find_pending_completion(shared);
            if (slotIndex < 0) {
                return true;
            }
            sequence = shared.slots[static_cast<size_t>(slotIndex)].sequence;
        }

        try {
            if (operations.onConsumed) {
                operations.onConsumed(slotIndex, sequence);
            }
        } catch (...) {
            std::lock_guard<std::mutex> lock(shared.mutex);
            request_stop_locked(
                shared,
                SuperResOrderedPipelineStepResult::failed(kCoordinatorFailureCode)
            );
            return false;
        }

        {
            std::lock_guard<std::mutex> lock(shared.mutex);
            Slot& slot = shared.slots[static_cast<size_t>(slotIndex)];
            slot.completionPending = false;
            slot.sequence = -1;
            shared.changed.notify_all();
        }
    }
}

SuperResOrderedPipelineProducerControl poll_producer_control(
    const SuperResOrderedPipelineOperations& operations,
    int sequence
) noexcept {
    if (!operations.pollProducerControl) {
        return SuperResOrderedPipelineProducerControl{};
    }
    try {
        return operations.pollProducerControl(sequence);
    } catch (...) {
        return SuperResOrderedPipelineProducerControl{1, true, kCoordinatorFailureCode};
    }
}

SuperResOrderedPipelineStepResult safe_produce(
    const SuperResOrderedPipelineOperations& operations,
    int slotIndex,
    int sequence,
    const SuperResOrderedPipelineCancellationToken& token
) noexcept {
    try {
        return operations.produce(slotIndex, sequence, token);
    } catch (...) {
        return SuperResOrderedPipelineStepResult::failed(kCoordinatorFailureCode);
    }
}

SuperResOrderedPipelineTelemetry snapshot_telemetry(SharedState& shared) {
    std::lock_guard<std::mutex> lock(shared.mutex);
    account_activity_locked(shared, Clock::now());
    shared.telemetry.producerWaitMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        shared.producerWait
    ).count();
    shared.telemetry.consumerWaitMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        shared.consumerWait
    ).count();
    shared.telemetry.overlapMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        shared.overlap
    ).count();
    return shared.telemetry;
}
} // namespace

SuperResOrderedPipelineRunResult run_superres_ordered_pipeline(
    const SuperResOrderedPipelineOperations& operations
) {
    if (operations.itemCount < 0 || !operations.produce || !operations.consume) {
        return SuperResOrderedPipelineRunResult{
            SuperResOrderedPipelineStepResult::failed(kCoordinatorFailureCode),
            SuperResOrderedPipelineTelemetry{}
        };
    }
    if (operations.itemCount == 0) {
        return SuperResOrderedPipelineRunResult{
            SuperResOrderedPipelineStepResult::ok(),
            SuperResOrderedPipelineTelemetry{}
        };
    }

    const int maximumDepth = std::clamp(operations.maximumDepth, 1, 2);
    std::unique_ptr<SharedState> sharedStorage;
    try {
        sharedStorage = std::make_unique<SharedState>(maximumDepth);
    } catch (...) {
        return SuperResOrderedPipelineRunResult{
            SuperResOrderedPipelineStepResult::initialization_failed(
                kCoordinatorFailureCode
            ),
            SuperResOrderedPipelineTelemetry{}
        };
    }
    SharedState& shared = *sharedStorage;
    const SuperResOrderedPipelineCancellationToken token =
        SuperResOrderedPipelineCancellationTokenFactory::make(&shared.stopLatch);

    std::thread consumer;
    try {
        const std::function<void()> consumerWork = [&operations, &shared, token]() {
            run_consumer(operations, shared, token);
        };
        consumer = operations.launchConsumer
            ? operations.launchConsumer(consumerWork)
            : std::thread(consumerWork);
        if (!consumer.joinable()) {
            return SuperResOrderedPipelineRunResult{
                SuperResOrderedPipelineStepResult::initialization_failed(
                    kCoordinatorFailureCode
                ),
                SuperResOrderedPipelineTelemetry{}
            };
        }
    } catch (...) {
        return SuperResOrderedPipelineRunResult{
            SuperResOrderedPipelineStepResult::initialization_failed(kCoordinatorFailureCode),
            SuperResOrderedPipelineTelemetry{}
        };
    }

    int sequence = 0;
    bool producerFinishedEarly = false;
    for (; sequence < operations.itemCount;) {
        if (!acknowledge_completions_on_producer(operations, shared)) {
            break;
        }

        const SuperResOrderedPipelineProducerControl control =
            poll_producer_control(operations, sequence);
        const int desiredDepth = std::clamp(control.desiredDepth, 1, maximumDepth);
        shared.telemetry.effectiveDepth = std::max(
            shared.telemetry.effectiveDepth,
            desiredDepth
        );
        if (control.shouldCancel) {
            std::lock_guard<std::mutex> lock(shared.mutex);
            request_stop_locked(
                shared,
                SuperResOrderedPipelineStepResult::cancelled(control.cancellationErrorCode)
            );
            break;
        }

        int slotIndex = -1;
        {
            std::unique_lock<std::mutex> lock(shared.mutex);
            if (shared.stopping) {
                break;
            }
            slotIndex = find_reusable_slot(shared);
            if (slotIndex < 0 || occupied_slot_count(shared) >= desiredDepth) {
                const Clock::time_point waitStart = Clock::now();
                shared.changed.wait_for(lock, kProducerPollInterval);
                shared.producerWait += Clock::now() - waitStart;
                continue;
            }
            Slot& slot = shared.slots[static_cast<size_t>(slotIndex)];
            slot.producerReserved = true;
            slot.sequence = sequence;
            shared.telemetry.peakOccupiedSlots = std::max(
                shared.telemetry.peakOccupiedSlots,
                occupied_slot_count(shared)
            );
            set_producer_active_locked(shared, true);
        }

        const SuperResOrderedPipelineStepResult result = safe_produce(
            operations,
            slotIndex,
            sequence,
            token
        );

        {
            std::lock_guard<std::mutex> lock(shared.mutex);
            set_producer_active_locked(shared, false);
            Slot& slot = shared.slots[static_cast<size_t>(slotIndex)];
            slot.producerReserved = false;
            if (result.kind == SuperResOrderedPipelineStepKind::Ok && !shared.stopping &&
                !token.stop_requested()) {
                slot.state = SlotState::GpuReady;
                ++sequence;
            } else if (result.kind == SuperResOrderedPipelineStepKind::Finished &&
                       !shared.stopping && !token.stop_requested()) {
                slot.state = SlotState::Free;
                slot.sequence = -1;
                producerFinishedEarly = true;
            } else {
                slot.state = SlotState::Free;
                slot.sequence = -1;
                request_stop_locked(
                    shared,
                    result.kind == SuperResOrderedPipelineStepKind::Ok
                        ? SuperResOrderedPipelineStepResult::cancelled(0)
                        : result
                );
            }
            shared.changed.notify_all();
        }
        if (producerFinishedEarly) {
            break;
        }
        if (result.kind == SuperResOrderedPipelineStepKind::Ok && operations.onProduced) {
            try {
                operations.onProduced(slotIndex, sequence - 1);
            } catch (...) {
                std::lock_guard<std::mutex> lock(shared.mutex);
                request_stop_locked(
                    shared,
                    SuperResOrderedPipelineStepResult::failed(kCoordinatorFailureCode)
                );
                break;
            }
        }
    }

    {
        std::lock_guard<std::mutex> lock(shared.mutex);
        shared.producerDone = true;
        shared.changed.notify_all();
    }

    while (true) {
        {
            std::lock_guard<std::mutex> lock(shared.mutex);
            if (shared.stopping ||
                (shared.producerDone && occupied_slot_count(shared) == 0)) {
                break;
            }
        }
        const SuperResOrderedPipelineProducerControl control =
            poll_producer_control(operations, sequence);
        if (control.shouldCancel) {
            std::lock_guard<std::mutex> lock(shared.mutex);
            request_stop_locked(
                shared,
                SuperResOrderedPipelineStepResult::cancelled(control.cancellationErrorCode)
            );
            break;
        }
        if (!acknowledge_completions_on_producer(operations, shared)) {
            break;
        }
        std::unique_lock<std::mutex> lock(shared.mutex);
        if (shared.stopping || (shared.producerDone && occupied_slot_count(shared) == 0)) {
            break;
        }
        const Clock::time_point waitStart = Clock::now();
        shared.changed.wait_for(lock, kProducerPollInterval);
        shared.producerWait += Clock::now() - waitStart;
    }

    {
        std::lock_guard<std::mutex> lock(shared.mutex);
        shared.changed.notify_all();
    }
    consumer.join();

    SuperResOrderedPipelineStepResult terminal;
    {
        std::lock_guard<std::mutex> lock(shared.mutex);
        terminal = shared.stopping ? shared.terminal : SuperResOrderedPipelineStepResult::ok();
    }
    return SuperResOrderedPipelineRunResult{terminal, snapshot_telemetry(shared)};
}
