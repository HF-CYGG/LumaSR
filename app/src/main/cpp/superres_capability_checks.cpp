#include "superres_capability.h"

#include <limits>

namespace {

constexpr int gpu_initialization_attempts_for_two_requests(
    int createResult,
    int gpuCount
) {
    int attempts = 0;
    SuperResGpuInitState state = SuperResGpuInitState::Uninitialized;
    if (should_attempt_gpu_initialization(state)) {
        ++attempts;
        state = transition_gpu_init_state(state, createResult, gpuCount);
    }
    if (should_attempt_gpu_initialization(state)) {
        ++attempts;
    }
    return attempts;
}

constexpr SuperResGpuInitState kFailedGpuState = transition_gpu_init_state(
    SuperResGpuInitState::Uninitialized,
    -1,
    0
);
static_assert(should_attempt_gpu_initialization(SuperResGpuInitState::Uninitialized));
static_assert(kFailedGpuState == SuperResGpuInitState::Unavailable);
static_assert(!should_attempt_gpu_initialization(kFailedGpuState));
static_assert(transition_gpu_init_state(kFailedGpuState, 0, 1) == kFailedGpuState);
static_assert(gpu_initialization_attempts_for_two_requests(-1, 0) == 1);

constexpr SuperResGpuInitState kAvailableGpuState = transition_gpu_init_state(
    SuperResGpuInitState::Uninitialized,
    0,
    1
);
static_assert(kAvailableGpuState == SuperResGpuInitState::Available);
static_assert(!should_attempt_gpu_initialization(kAvailableGpuState));
static_assert(transition_gpu_init_state(kAvailableGpuState, -1, 0) == kAvailableGpuState);
static_assert(gpu_initialization_attempts_for_two_requests(0, 1) == 1);

constexpr SuperResGpuInitState kEmptyGpuState = transition_gpu_init_state(
    SuperResGpuInitState::Uninitialized,
    0,
    0
);
static_assert(kEmptyGpuState == SuperResGpuInitState::Unavailable);
static_assert(should_destroy_empty_gpu_instance(0, 0));
static_assert(!should_destroy_empty_gpu_instance(-1, 0));
static_assert(!should_destroy_empty_gpu_instance(0, 1));

constexpr SuperResCpuTopology kNormalizedTopology = normalize_cpu_topology(8, 3, 5);
static_assert(kNormalizedTopology.physicalCoreCount == 8);
static_assert(kNormalizedTopology.bigCoreCount == 3);
static_assert(kNormalizedTopology.littleCoreCount == 5);

constexpr SuperResCpuTopology kInvalidTopology = normalize_cpu_topology(0, 4, 4);
static_assert(kInvalidTopology.physicalCoreCount == 0);
static_assert(kInvalidTopology.bigCoreCount == 0);
static_assert(kInvalidTopology.littleCoreCount == 0);

constexpr SuperResCpuTopology kBoundedTopology = normalize_cpu_topology(6, 8, 5);
static_assert(kBoundedTopology.bigCoreCount == 6);
static_assert(kBoundedTopology.littleCoreCount == 0);

static_assert(normalize_gpu_rough_score(0) == 0);
static_assert(normalize_gpu_rough_score(75) == 75);
static_assert(
    normalize_gpu_rough_score(std::numeric_limits<unsigned int>::max()) ==
        std::numeric_limits<int>::max()
);
static_assert(is_qualcomm_vulkan_vendor(0x5143u));
static_assert(!is_qualcomm_vulkan_vendor(0x13b5u));
static_assert(vulkan_uint32_to_int64(0u) == 0LL);
static_assert(vulkan_uint32_to_int64(std::numeric_limits<unsigned int>::max()) == 4294967295LL);
static_assert(sanitized_capability_length(nullptr) == 0);
static_assert(sanitized_capability_length("\t Mali-G715 \n") == 9);
static_assert(sanitized_capability_length("     ") == 0);
static_assert(sanitized_capability_length(
    "0123456789abcdef0123456789abcdef0123456789abcdef"
    "0123456789abcdef0123456789abcdef0123456789abcdef"
    "0123456789abcdef0123456789abcdef0123456789abcdef",
    kMaxCapabilityStringLength + 32
) == kMaxCapabilityStringLength);

}

bool run_capability_normalization_runtime_checks() {
    const unsigned char cacheUuid[16] = {
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff
    };
    const std::string bounded = sanitize_capability_text(
        "\t  Vulkan device with a deliberately long diagnostic identity  \n",
        16
    );
    return bounded == "Vulkan device wi" &&
        pipeline_cache_uuid_hex(cacheUuid) == "00112233445566778899aabbccddeeff" &&
        pipeline_cache_uuid_hex(nullptr).empty() &&
        sanitize_capability_text("\r\n\t").empty() &&
        sanitize_capability_text(nullptr).empty();
}
