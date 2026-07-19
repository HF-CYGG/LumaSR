#include "superres_rgb_transport_checks.h"

#include "superres_rgb_transport.h"

#include <limits>

namespace {

constexpr size_t kMiB = 1024u * 1024u;

bool exact_budget_boundaries_check() {
    const size_t budgets[] = {16u * kMiB, 48u * kMiB, 96u * kMiB};
    for (size_t budget : budgets) {
        const SuperResRgbTransportDecision exact =
            assess_superres_rgb_transport_bytes(budget, budget);
        const SuperResRgbTransportDecision over =
            assess_superres_rgb_transport_bytes(budget + 1u, budget);
        const SuperResRgbTransportStatus expectedOverStatus =
            budget == kSuperResRgbTransportHardLimitBytes
            ? SuperResRgbTransportStatus::HardLimitExceeded
            : SuperResRgbTransportStatus::BudgetExceeded;
        if (exact.status != SuperResRgbTransportStatus::Eligible ||
            exact.byteSize != budget || exact.effectiveBudgetBytes != budget ||
            over.status != expectedOverStatus) {
            return false;
        }
    }
    return true;
}

bool hard_limit_and_invalid_budget_check() {
    const SuperResRgbTransportDecision hardLimited =
        assess_superres_rgb_transport_bytes(96u * kMiB + 1u, 128u * kMiB);
    const SuperResRgbTransportDecision invalidBudget =
        assess_superres_rgb_transport_bytes(1u, 0u);
    return hardLimited.status == SuperResRgbTransportStatus::HardLimitExceeded &&
        hardLimited.effectiveBudgetBytes == 96u * kMiB &&
        invalidBudget.status == SuperResRgbTransportStatus::InvalidBudget;
}

bool checked_rgb_layout_and_overflow_check() {
    size_t validByteSize = 0;
    const bool valid = checked_superres_rgb_transport_bytes(2u, 3u, validByteSize);
    size_t overflowByteSize = 123u;
    const bool overflow = checked_superres_rgb_transport_bytes(
        std::numeric_limits<size_t>::max(),
        2u,
        overflowByteSize
    );
    const SuperResRgbTransportDecision invalidDimensions =
        assess_superres_rgb_transport(0, 1, 16u * kMiB);
    return valid && validByteSize == 18u && !overflow && overflowByteSize == 0u &&
        invalidDimensions.status == SuperResRgbTransportStatus::InvalidDimensions;
}

bool layout_budget_decision_check() {
    const SuperResRgbTransportDecision eligible =
        assess_superres_rgb_transport(1024, 1024, 16u * kMiB);
    const SuperResRgbTransportDecision exceeded =
        assess_superres_rgb_transport(4096, 4096, 16u * kMiB);
    return eligible.status == SuperResRgbTransportStatus::Eligible &&
        eligible.byteSize == 3u * 1024u * 1024u &&
        exceeded.status == SuperResRgbTransportStatus::BudgetExceeded;
}

} // namespace

bool run_superres_rgb_transport_runtime_checks() {
    return exact_budget_boundaries_check() && hard_limit_and_invalid_budget_check() &&
        checked_rgb_layout_and_overflow_check() && layout_budget_decision_check();
}
