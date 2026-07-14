#include "superres_capability.h"

#include <limits>

namespace {

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
    const std::string bounded = sanitize_capability_text(
        "\t  Vulkan device with a deliberately long diagnostic identity  \n",
        16
    );
    return bounded == "Vulkan device wi" &&
        sanitize_capability_text("\r\n\t").empty() &&
        sanitize_capability_text(nullptr).empty();
}
