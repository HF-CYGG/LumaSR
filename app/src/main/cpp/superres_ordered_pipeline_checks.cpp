#include "superres_ordered_pipeline_checks.h"

#include "superres_ordered_pipeline.h"

#include <atomic>
#include <chrono>
#include <mutex>
#include <thread>
#include <vector>

namespace {
using namespace std::chrono_literals;

bool is_sequence(const std::vector<int>& values, int count) {
    if (static_cast<int>(values.size()) != count) {
        return false;
    }
    for (int index = 0; index < count; ++index) {
        if (values[static_cast<size_t>(index)] != index) {
            return false;
        }
    }
    return true;
}

bool initial_depth_one_dispatches_serial_without_ordered_initialization_check() {
    int serialCalls = 0;
    int orderedCalls = 0;
    const int result = dispatch_superres_vulkan_pipeline(
        1,
        [&]() {
            ++serialCalls;
            return 17;
        },
        [&]() {
            ++orderedCalls;
            return SuperResVulkanPipelineAttemptResult{23, false, false};
        }
    );
    return result == 17 && serialCalls == 1 && orderedCalls == 0;
}

bool ordered_initialization_failure_before_output_retries_serial_check() {
    int serialCalls = 0;
    int orderedCalls = 0;
    const int result = dispatch_superres_vulkan_pipeline(
        2,
        [&]() {
            ++serialCalls;
            return 29;
        },
        [&]() {
            ++orderedCalls;
            return SuperResVulkanPipelineAttemptResult{31, true, false};
        }
    );
    return result == 29 && serialCalls == 1 && orderedCalls == 1;
}

bool consumer_thread_initialization_failure_is_distinct_check() {
    SuperResOrderedPipelineOperations operations;
    operations.itemCount = 1;
    operations.maximumDepth = 2;
    operations.produce = [](int, int, const SuperResOrderedPipelineCancellationToken&) {
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.consume = [](int, int, const SuperResOrderedPipelineCancellationToken&) {
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.launchConsumer = [](std::function<void()>) -> std::thread {
        throw 1;
    };

    const SuperResOrderedPipelineRunResult result = run_superres_ordered_pipeline(operations);
    return result.step.kind == SuperResOrderedPipelineStepKind::InitializationFailed;
}

bool ordered_failure_after_output_never_retries_serial_check() {
    int serialCalls = 0;
    const int result = dispatch_superres_vulkan_pipeline(
        2,
        [&]() {
            ++serialCalls;
            return 37;
        },
        []() {
            return SuperResVulkanPipelineAttemptResult{41, true, true};
        }
    );
    return result == 41 && serialCalls == 0;
}

bool depth_one_preserves_order_and_producer_callback_thread_check() {
    const std::thread::id producerThread = std::this_thread::get_id();
    std::thread::id consumerThread;
    std::mutex recordsMutex;
    std::vector<int> produced;
    std::vector<int> consumed;
    std::vector<int> acknowledged;
    bool callbacksStayedOnProducer = true;

    SuperResOrderedPipelineOperations operations;
    operations.itemCount = 4;
    operations.maximumDepth = 2;
    operations.pollProducerControl = [&](int) {
        callbacksStayedOnProducer = callbacksStayedOnProducer &&
            std::this_thread::get_id() == producerThread;
        return SuperResOrderedPipelineProducerControl{1, false};
    };
    operations.produce = [&](int, int sequence, const SuperResOrderedPipelineCancellationToken&) {
        callbacksStayedOnProducer = callbacksStayedOnProducer &&
            std::this_thread::get_id() == producerThread;
        std::lock_guard<std::mutex> lock(recordsMutex);
        produced.push_back(sequence);
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.onProduced = [&](int, int) {
        callbacksStayedOnProducer = callbacksStayedOnProducer &&
            std::this_thread::get_id() == producerThread;
    };
    operations.consume = [&](int, int sequence, const SuperResOrderedPipelineCancellationToken&) {
        consumerThread = std::this_thread::get_id();
        std::lock_guard<std::mutex> lock(recordsMutex);
        consumed.push_back(sequence);
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.onConsumed = [&](int, int sequence) {
        callbacksStayedOnProducer = callbacksStayedOnProducer &&
            std::this_thread::get_id() == producerThread;
        std::lock_guard<std::mutex> lock(recordsMutex);
        acknowledged.push_back(sequence);
    };

    const SuperResOrderedPipelineRunResult result = run_superres_ordered_pipeline(operations);
    return result.step.kind == SuperResOrderedPipelineStepKind::Ok &&
        result.telemetry.effectiveDepth == 1 && result.telemetry.peakOccupiedSlots == 1 &&
        callbacksStayedOnProducer && consumerThread != std::thread::id{} &&
        consumerThread != producerThread && is_sequence(produced, 4) &&
        is_sequence(consumed, 4) && is_sequence(acknowledged, 4);
}

bool producer_can_finish_before_maximum_item_count_check() {
    std::vector<int> consumed;
    SuperResOrderedPipelineOperations operations;
    operations.itemCount = 6;
    operations.maximumDepth = 2;
    operations.pollProducerControl = [](int) {
        return SuperResOrderedPipelineProducerControl{2, false};
    };
    operations.produce = [](int, int sequence, const SuperResOrderedPipelineCancellationToken&) {
        return sequence == 2
            ? SuperResOrderedPipelineStepResult::finished()
            : SuperResOrderedPipelineStepResult::ok();
    };
    operations.consume = [&](int, int sequence, const SuperResOrderedPipelineCancellationToken&) {
        consumed.push_back(sequence);
        return SuperResOrderedPipelineStepResult::ok();
    };

    const SuperResOrderedPipelineRunResult result = run_superres_ordered_pipeline(operations);
    return result.step.kind == SuperResOrderedPipelineStepKind::Ok &&
        is_sequence(consumed, 2);
}

bool cancellation_is_polled_while_draining_consumer_check() {
    std::atomic<int> polls{0};
    SuperResOrderedPipelineOperations operations;
    operations.itemCount = 1;
    operations.maximumDepth = 1;
    operations.pollProducerControl = [&](int) {
        const int poll = ++polls;
        return SuperResOrderedPipelineProducerControl{1, poll >= 2, 66};
    };
    operations.produce = [](int, int, const SuperResOrderedPipelineCancellationToken&) {
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.consume = [](int, int, const SuperResOrderedPipelineCancellationToken& token) {
        std::this_thread::sleep_for(40ms);
        return token.stop_requested()
            ? SuperResOrderedPipelineStepResult::cancelled(66)
            : SuperResOrderedPipelineStepResult::ok();
    };

    const SuperResOrderedPipelineRunResult result = run_superres_ordered_pipeline(operations);
    return result.step.kind == SuperResOrderedPipelineStepKind::Cancelled &&
        result.step.errorCode == 66 && polls.load() >= 2;
}

bool depth_two_overlaps_and_applies_backpressure_check() {
    std::atomic<int> produced{0};
    std::atomic<int> consumed{0};

    SuperResOrderedPipelineOperations operations;
    operations.itemCount = 5;
    operations.maximumDepth = 2;
    operations.pollProducerControl = [](int) {
        return SuperResOrderedPipelineProducerControl{2, false};
    };
    operations.produce = [&](int, int, const SuperResOrderedPipelineCancellationToken&) {
        ++produced;
        std::this_thread::sleep_for(25ms);
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.consume = [&](int, int, const SuperResOrderedPipelineCancellationToken&) {
        ++consumed;
        std::this_thread::sleep_for(45ms);
        return SuperResOrderedPipelineStepResult::ok();
    };

    const SuperResOrderedPipelineRunResult result = run_superres_ordered_pipeline(operations);
    return result.step.kind == SuperResOrderedPipelineStepKind::Ok && produced.load() == 5 &&
        consumed.load() == 5 && result.telemetry.effectiveDepth == 2 &&
        result.telemetry.peakOccupiedSlots == 2 && result.telemetry.producerWaitMs > 0 &&
        result.telemetry.consumerWaitMs > 0 && result.telemetry.overlapMs > 0;
}

bool dynamic_depth_downgrade_drains_second_slot_check() {
    std::atomic<int> acknowledged{0};
    std::atomic<bool> thirdStartedAfterDrain{false};

    SuperResOrderedPipelineOperations operations;
    operations.itemCount = 4;
    operations.maximumDepth = 2;
    operations.pollProducerControl = [](int sequence) {
        return SuperResOrderedPipelineProducerControl{sequence < 2 ? 2 : 1, false};
    };
    operations.produce = [&](int, int sequence, const SuperResOrderedPipelineCancellationToken&) {
        if (sequence == 2) {
            thirdStartedAfterDrain.store(acknowledged.load() == 2);
        }
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.consume = [](int, int, const SuperResOrderedPipelineCancellationToken&) {
        std::this_thread::sleep_for(15ms);
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.onConsumed = [&](int, int) { ++acknowledged; };

    const SuperResOrderedPipelineRunResult result = run_superres_ordered_pipeline(operations);
    return result.step.kind == SuperResOrderedPipelineStepKind::Ok && acknowledged.load() == 4 &&
        thirdStartedAfterDrain.load() && result.telemetry.effectiveDepth == 2 &&
        result.telemetry.peakOccupiedSlots == 2;
}

bool producer_failure_wakes_and_joins_consumer_check() {
    const auto start = std::chrono::steady_clock::now();
    SuperResOrderedPipelineOperations operations;
    operations.itemCount = 4;
    operations.maximumDepth = 2;
    operations.pollProducerControl = [](int) {
        return SuperResOrderedPipelineProducerControl{2, false};
    };
    operations.produce = [](int, int sequence, const SuperResOrderedPipelineCancellationToken&) {
        return sequence == 1
            ? SuperResOrderedPipelineStepResult::failed(31)
            : SuperResOrderedPipelineStepResult::ok();
    };
    operations.consume = [](int, int, const SuperResOrderedPipelineCancellationToken&) {
        std::this_thread::sleep_for(10ms);
        return SuperResOrderedPipelineStepResult::ok();
    };

    const SuperResOrderedPipelineRunResult result = run_superres_ordered_pipeline(operations);
    return result.step.kind == SuperResOrderedPipelineStepKind::Failed &&
        result.step.errorCode == 31 &&
        std::chrono::steady_clock::now() - start < 1s;
}

bool consumer_failure_wakes_backpressured_producer_check() {
    const auto start = std::chrono::steady_clock::now();
    SuperResOrderedPipelineOperations operations;
    operations.itemCount = 8;
    operations.maximumDepth = 2;
    operations.pollProducerControl = [](int) {
        return SuperResOrderedPipelineProducerControl{2, false};
    };
    operations.produce = [](int, int, const SuperResOrderedPipelineCancellationToken&) {
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.consume = [](int, int sequence, const SuperResOrderedPipelineCancellationToken&) {
        return sequence == 0
            ? SuperResOrderedPipelineStepResult::failed(42)
            : SuperResOrderedPipelineStepResult::ok();
    };

    const SuperResOrderedPipelineRunResult result = run_superres_ordered_pipeline(operations);
    return result.step.kind == SuperResOrderedPipelineStepKind::Failed &&
        result.step.errorCode == 42 &&
        std::chrono::steady_clock::now() - start < 1s;
}

bool producer_cancellation_is_latched_check() {
    std::atomic<int> produced{0};
    std::atomic<int> consumed{0};
    SuperResOrderedPipelineOperations operations;
    operations.itemCount = 5;
    operations.maximumDepth = 2;
    operations.pollProducerControl = [](int sequence) {
        return SuperResOrderedPipelineProducerControl{2, sequence >= 1, 77};
    };
    operations.produce = [&](int, int, const SuperResOrderedPipelineCancellationToken& token) {
        if (token.stop_requested()) {
            return SuperResOrderedPipelineStepResult::cancelled(77);
        }
        ++produced;
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.consume = [&](int, int, const SuperResOrderedPipelineCancellationToken& token) {
        if (token.stop_requested()) {
            return SuperResOrderedPipelineStepResult::cancelled(77);
        }
        ++consumed;
        return SuperResOrderedPipelineStepResult::ok();
    };

    const SuperResOrderedPipelineRunResult result = run_superres_ordered_pipeline(operations);
    return result.step.kind == SuperResOrderedPipelineStepKind::Cancelled &&
        result.step.errorCode == 77 && produced.load() == 1 && consumed.load() <= 1;
}

bool consumer_cancellation_stops_future_acknowledgements_check() {
    std::atomic<int> acknowledgements{0};
    std::atomic<bool> secondConsumerSawLatch{false};
    SuperResOrderedPipelineOperations operations;
    operations.itemCount = 4;
    operations.maximumDepth = 2;
    operations.pollProducerControl = [](int) {
        return SuperResOrderedPipelineProducerControl{2, false};
    };
    operations.produce = [](int, int, const SuperResOrderedPipelineCancellationToken&) {
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.consume = [&](int, int sequence, const SuperResOrderedPipelineCancellationToken& token) {
        if (sequence == 0) {
            return SuperResOrderedPipelineStepResult::cancelled(88);
        }
        secondConsumerSawLatch.store(token.stop_requested());
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.onConsumed = [&](int, int) { ++acknowledgements; };

    const SuperResOrderedPipelineRunResult result = run_superres_ordered_pipeline(operations);
    return result.step.kind == SuperResOrderedPipelineStepKind::Cancelled &&
        result.step.errorCode == 88 && acknowledgements.load() == 0 &&
        !secondConsumerSawLatch.load();
}
} // namespace

bool run_superres_ordered_pipeline_runtime_checks() {
    return initial_depth_one_dispatches_serial_without_ordered_initialization_check() &&
        ordered_initialization_failure_before_output_retries_serial_check() &&
        consumer_thread_initialization_failure_is_distinct_check() &&
        ordered_failure_after_output_never_retries_serial_check() &&
        depth_one_preserves_order_and_producer_callback_thread_check() &&
        producer_can_finish_before_maximum_item_count_check() &&
        cancellation_is_polled_while_draining_consumer_check() &&
        depth_two_overlaps_and_applies_backpressure_check() &&
        dynamic_depth_downgrade_drains_second_slot_check() &&
        producer_failure_wakes_and_joins_consumer_check() &&
        consumer_failure_wakes_backpressured_producer_check() &&
        producer_cancellation_is_latched_check() &&
        consumer_cancellation_stops_future_acknowledgements_check();
}
