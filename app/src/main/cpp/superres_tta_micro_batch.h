#pragma once

#include "superres_engine.h"
#include "superres_execution_profile.h"

#include <array>
#include <cstddef>
#include <utility>

struct SuperResTtaPassGroup {
    size_t firstPass;
    size_t passCount;
};

constexpr std::array<SuperResTtaPassGroup, 4> superres_tta_pass_groups() noexcept {
    return {{
        {0u, 2u},
        {2u, 2u},
        {4u, 2u},
        {6u, 2u}
    }};
}

enum class SuperResTtaMicroBatchStatus {
    Ok,
    Cancelled,
    Failed
};

struct SuperResTtaMicroBatchRunResult {
    SuperResTtaMicroBatchStatus status;
    size_t completedGroups;
    size_t completedPasses;
};

// The boundary callback is evaluated immediately before recording each group and again after its
// submit completes. Cancelled latches user/critical stops, while Failed requests a full safe-TTA
// retry when runtime policy withdraws the calibrated two-pass batch.
template <typename CheckBoundary, typename ExecuteGroup, typename ConsumePass>
SuperResTtaMicroBatchRunResult run_superres_tta_micro_batches(
    CheckBoundary&& checkBoundary,
    ExecuteGroup&& executeGroup,
    ConsumePass&& consumePass
) {
    size_t completedGroups = 0u;
    size_t completedPasses = 0u;
    for (const SuperResTtaPassGroup& group : superres_tta_pass_groups()) {
        const SuperResTtaMicroBatchStatus beforeGroup = checkBoundary();
        if (beforeGroup != SuperResTtaMicroBatchStatus::Ok) {
            return {beforeGroup, completedGroups, completedPasses};
        }
        const SuperResTtaMicroBatchStatus execution = executeGroup(group);
        if (execution != SuperResTtaMicroBatchStatus::Ok) {
            return {execution, completedGroups, completedPasses};
        }
        const SuperResTtaMicroBatchStatus afterSubmit = checkBoundary();
        if (afterSubmit != SuperResTtaMicroBatchStatus::Ok) {
            return {afterSubmit, completedGroups, completedPasses};
        }
        for (size_t offset = 0u; offset < group.passCount; ++offset) {
            const SuperResTtaMicroBatchStatus consumption =
                consumePass(group.firstPass + offset);
            if (consumption != SuperResTtaMicroBatchStatus::Ok) {
                return {consumption, completedGroups, completedPasses};
            }
            ++completedPasses;
        }
        ++completedGroups;
    }
    return {SuperResTtaMicroBatchStatus::Ok, completedGroups, completedPasses};
}

enum class SuperResTtaRetryDisposition {
    ReturnResult,
    ReturnCancelled,
    ReturnPermanentFailure,
    RetrySafeTta
};

constexpr SuperResTtaRetryDisposition classify_optimized_tta_result(
    SuperResNativeCode code
) noexcept {
    switch (code) {
    case SuperResNativeCode::Ok:
        return SuperResTtaRetryDisposition::ReturnResult;
    case SuperResNativeCode::Cancelled:
        return SuperResTtaRetryDisposition::ReturnCancelled;
    case SuperResNativeCode::InputMissing:
    case SuperResNativeCode::ModelMissing:
    case SuperResNativeCode::InvalidParams:
        return SuperResTtaRetryDisposition::ReturnPermanentFailure;
    default:
        return SuperResTtaRetryDisposition::RetrySafeTta;
    }
}

inline bool has_optimized_tta_configuration(
    const SuperResNativeParams& params
) noexcept {
    return params.tta &&
        params.nativeBackend == static_cast<int>(SuperResNativeBackend::Optimized) &&
        params.inferencePrecision == static_cast<int>(SuperResInferencePrecision::Fp16) &&
        params.commandBatchSize == 2;
}

inline bool should_request_optimized_vulkan_tta(
    const SuperResNativeParams& params,
    bool useVulkan,
    bool transactionalOutput
) noexcept {
    return transactionalOutput && useVulkan && has_optimized_tta_configuration(params);
}

inline bool should_run_safe_tta_directly(
    const SuperResNativeParams& params,
    bool useVulkan,
    bool transactionalOutput
) noexcept {
    return has_optimized_tta_configuration(params) && (!useVulkan || !transactionalOutput);
}

inline bool should_run_optimized_vulkan_tta(
    const SuperResNativeParams& params,
    const SuperResExecutionProfile& profile,
    bool useVulkan,
    bool transactionalOutput
) noexcept {
    return should_request_optimized_vulkan_tta(params, useVulkan, transactionalOutput) &&
        profile.effectiveBackend == SuperResNativeBackend::Optimized &&
        profile.effectivePrecision == SuperResInferencePrecision::Fp16 &&
        profile.commandBatchSize == 2;
}

inline SuperResNativeParams make_safe_tta_params(
    const SuperResNativeParams& params,
    int retryIncrement
) {
    SuperResNativeParams safe = params;
    safe.nativeBackend = static_cast<int>(SuperResNativeBackend::LegacySafe);
    safe.inferencePrecision = static_cast<int>(SuperResInferencePrecision::Fp32);
    safe.commandBatchSize = 1;
    safe.retryCount = params.retryCount + retryIncrement;
    return safe;
}

inline SuperResNativeParams make_safe_tta_retry_params(
    const SuperResNativeParams& params
) {
    return make_safe_tta_params(params, 1);
}

template <typename Attempt,
          typename ResultCode,
          typename ShouldStop,
          typename CancelledResult,
          typename BeforeSafeRetry>
auto run_superres_tta_attempts(
    const SuperResNativeParams& params,
    bool useVulkan,
    bool transactionalOutput,
    Attempt&& attempt,
    ResultCode&& resultCode,
    ShouldStop&& shouldStop,
    CancelledResult&& cancelledResult,
    BeforeSafeRetry&& beforeSafeRetry
) -> decltype(attempt(params)) {
    if (should_run_safe_tta_directly(params, useVulkan, transactionalOutput)) {
        return attempt(make_safe_tta_params(params, 0));
    }

    auto result = attempt(params);
    const SuperResNativeCode code = resultCode(result);
    if (!should_request_optimized_vulkan_tta(params, useVulkan, transactionalOutput) ||
        classify_optimized_tta_result(code) != SuperResTtaRetryDisposition::RetrySafeTta) {
        return result;
    }
    if (shouldStop()) {
        return cancelledResult();
    }
    beforeSafeRetry(code);
    return attempt(make_safe_tta_retry_params(params));
}

enum class SuperResTtaOutputStep {
    CanonicalFloatFusion,
    CropWindow,
    RealCuganResidual,
    Rgb8Write
};

constexpr std::array<SuperResTtaOutputStep, 4> superres_tta_output_order() noexcept {
    return {{
        SuperResTtaOutputStep::CanonicalFloatFusion,
        SuperResTtaOutputStep::CropWindow,
        SuperResTtaOutputStep::RealCuganResidual,
        SuperResTtaOutputStep::Rgb8Write
    }};
}
