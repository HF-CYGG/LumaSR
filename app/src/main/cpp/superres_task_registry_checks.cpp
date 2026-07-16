#include "superres_task_registry_checks.h"

#include "superres_task_registry.h"

#include <thread>

bool run_task_registry_runtime_checks() {
    SuperResTaskRegistry registry;

    // A cancel delivered before native registration belongs to no active invocation.
    registry.cancel("pre-start");
    if (!registry.register_task("pre-start") || registry.consume_cancelled("pre-start")) {
        return false;
    }
    registry.complete_task("pre-start");

    if (!registry.register_task("cancel-wins")) return false;
    registry.cancel("cancel-wins");
    bool published = false;
    bool aborted = false;
    const SuperResTaskPublishOutcome cancelledOutcome = registry.publish_or_cancel(
        "cancel-wins",
        [&published]() {
            published = true;
            return true;
        },
        [&aborted]() { aborted = true; }
    );
    if (cancelledOutcome != SuperResTaskPublishOutcome::Cancelled || published || !aborted) {
        return false;
    }
    registry.complete_task("cancel-wins");

    if (!registry.register_task("publish-wins")) return false;
    const SuperResTaskPublishOutcome publishedOutcome = registry.publish_or_cancel(
        "publish-wins",
        []() { return true; },
        []() {}
    );
    if (publishedOutcome != SuperResTaskPublishOutcome::Published) return false;
    // A late cancel may race before completion, but completion must clear it.
    registry.cancel("publish-wins");
    registry.complete_task("publish-wins");
    if (!registry.register_task("publish-wins") || registry.consume_cancelled("publish-wins")) {
        return false;
    }
    registry.complete_task("publish-wins");

    if (!registry.register_task("publish-cancel-race")) return false;
    std::thread lateCancel;
    const SuperResTaskPublishOutcome raceOutcome = registry.publish_or_cancel(
        "publish-cancel-race",
        [&registry, &lateCancel]() {
            lateCancel = std::thread([&registry]() { registry.cancel("publish-cancel-race"); });
            return true;
        },
        []() {}
    );
    lateCancel.join();
    registry.complete_task("publish-cancel-race");
    if (raceOutcome != SuperResTaskPublishOutcome::Published ||
        !registry.register_task("publish-cancel-race") ||
        registry.consume_cancelled("publish-cancel-race")) {
        return false;
    }
    registry.complete_task("publish-cancel-race");

    if (!registry.register_task("complete-cancel-race")) return false;
    std::thread completion([&registry]() { registry.complete_task("complete-cancel-race"); });
    std::thread cancellation([&registry]() { registry.cancel("complete-cancel-race"); });
    completion.join();
    cancellation.join();
    if (!registry.register_task("complete-cancel-race") ||
        registry.consume_cancelled("complete-cancel-race")) {
        return false;
    }
    registry.complete_task("complete-cancel-race");

    // Inactive cancel must not accumulate state for a future invocation.
    registry.cancel("inactive");
    if (!registry.register_task("inactive") || registry.consume_cancelled("inactive")) {
        return false;
    }
    registry.complete_task("inactive");
    return true;
}
