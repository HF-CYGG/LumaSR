#include "superres_tta_micro_batch_checks.h"

#include "superres_tta_micro_batch.h"

#include <array>
#include <vector>

namespace {
bool pass_group_plan_check() {
    constexpr auto groups = superres_tta_pass_groups();
    return groups.size() == 4u &&
        groups[0].firstPass == 0u && groups[0].passCount == 2u &&
        groups[1].firstPass == 2u && groups[1].passCount == 2u &&
        groups[2].firstPass == 4u && groups[2].passCount == 2u &&
        groups[3].firstPass == 6u && groups[3].passCount == 2u;
}

bool executor_preserves_group_and_fusion_order_check() {
    std::vector<size_t> executedGroups;
    std::vector<size_t> fusedPasses;
    const SuperResTtaMicroBatchRunResult result = run_superres_tta_micro_batches(
        []() { return SuperResTtaMicroBatchStatus::Ok; },
        [&executedGroups](const SuperResTtaPassGroup& group) {
            executedGroups.push_back(group.firstPass);
            return SuperResTtaMicroBatchStatus::Ok;
        },
        [&fusedPasses](size_t pass) {
            fusedPasses.push_back(pass);
            return SuperResTtaMicroBatchStatus::Ok;
        }
    );
    return result.status == SuperResTtaMicroBatchStatus::Ok &&
        result.completedGroups == 4u && result.completedPasses == 8u &&
        executedGroups == std::vector<size_t>({0u, 2u, 4u, 6u}) &&
        fusedPasses == std::vector<size_t>({0u, 1u, 2u, 3u, 4u, 5u, 6u, 7u});
}

bool executor_latches_cancellation_at_boundaries_check() {
    int boundaryCalls = 0;
    int executeCalls = 0;
    int consumeCalls = 0;
    const SuperResTtaMicroBatchRunResult beforeSecondGroup = run_superres_tta_micro_batches(
        [&boundaryCalls]() {
            ++boundaryCalls;
            return boundaryCalls >= 3
                ? SuperResTtaMicroBatchStatus::Cancelled
                : SuperResTtaMicroBatchStatus::Ok;
        },
        [&executeCalls](const SuperResTtaPassGroup&) {
            ++executeCalls;
            return SuperResTtaMicroBatchStatus::Ok;
        },
        [&consumeCalls](size_t) {
            ++consumeCalls;
            return SuperResTtaMicroBatchStatus::Ok;
        }
    );
    if (beforeSecondGroup.status != SuperResTtaMicroBatchStatus::Cancelled ||
        beforeSecondGroup.completedGroups != 1u || beforeSecondGroup.completedPasses != 2u ||
        executeCalls != 1 || consumeCalls != 2) {
        return false;
    }

    boundaryCalls = 0;
    executeCalls = 0;
    consumeCalls = 0;
    const SuperResTtaMicroBatchRunResult afterSubmit = run_superres_tta_micro_batches(
        [&boundaryCalls]() {
            ++boundaryCalls;
            return boundaryCalls >= 2
                ? SuperResTtaMicroBatchStatus::Cancelled
                : SuperResTtaMicroBatchStatus::Ok;
        },
        [&executeCalls](const SuperResTtaPassGroup&) {
            ++executeCalls;
            return SuperResTtaMicroBatchStatus::Ok;
        },
        [&consumeCalls](size_t) {
            ++consumeCalls;
            return SuperResTtaMicroBatchStatus::Ok;
        }
    );
    return afterSubmit.status == SuperResTtaMicroBatchStatus::Cancelled &&
        afterSubmit.completedGroups == 0u && afterSubmit.completedPasses == 0u &&
        executeCalls == 1 && consumeCalls == 0;
}

bool executor_classifies_runtime_batch_downgrade_as_safe_retry_check() {
    int boundaryCalls = 0;
    int executeCalls = 0;
    int consumeCalls = 0;
    const SuperResTtaMicroBatchRunResult result = run_superres_tta_micro_batches(
        [&boundaryCalls]() {
            ++boundaryCalls;
            return boundaryCalls >= 3
                ? SuperResTtaMicroBatchStatus::Failed
                : SuperResTtaMicroBatchStatus::Ok;
        },
        [&executeCalls](const SuperResTtaPassGroup&) {
            ++executeCalls;
            return SuperResTtaMicroBatchStatus::Ok;
        },
        [&consumeCalls](size_t) {
            ++consumeCalls;
            return SuperResTtaMicroBatchStatus::Ok;
        }
    );
    return result.status == SuperResTtaMicroBatchStatus::Failed &&
        result.completedGroups == 1u && result.completedPasses == 2u &&
        executeCalls == 1 && consumeCalls == 2;
}

bool executor_stops_on_execution_and_fusion_failure_check() {
    int executeCalls = 0;
    int consumeCalls = 0;
    const SuperResTtaMicroBatchRunResult executionFailure = run_superres_tta_micro_batches(
        []() { return SuperResTtaMicroBatchStatus::Ok; },
        [&executeCalls](const SuperResTtaPassGroup&) {
            ++executeCalls;
            return executeCalls == 2
                ? SuperResTtaMicroBatchStatus::Failed
                : SuperResTtaMicroBatchStatus::Ok;
        },
        [&consumeCalls](size_t) {
            ++consumeCalls;
            return SuperResTtaMicroBatchStatus::Ok;
        }
    );
    if (executionFailure.status != SuperResTtaMicroBatchStatus::Failed ||
        executionFailure.completedGroups != 1u || executionFailure.completedPasses != 2u ||
        executeCalls != 2 || consumeCalls != 2) {
        return false;
    }

    executeCalls = 0;
    consumeCalls = 0;
    const SuperResTtaMicroBatchRunResult fusionFailure = run_superres_tta_micro_batches(
        []() { return SuperResTtaMicroBatchStatus::Ok; },
        [&executeCalls](const SuperResTtaPassGroup&) {
            ++executeCalls;
            return SuperResTtaMicroBatchStatus::Ok;
        },
        [&consumeCalls](size_t pass) {
            ++consumeCalls;
            return pass == 3u
                ? SuperResTtaMicroBatchStatus::Failed
                : SuperResTtaMicroBatchStatus::Ok;
        }
    );
    return fusionFailure.status == SuperResTtaMicroBatchStatus::Failed &&
        fusionFailure.completedGroups == 1u && fusionFailure.completedPasses == 3u &&
        executeCalls == 2 && consumeCalls == 4;
}

bool optimized_gate_and_safe_retry_classification_check() {
    SuperResNativeParams params{};
    params.tta = true;
    params.nativeBackend = static_cast<int>(SuperResNativeBackend::Optimized);
    params.inferencePrecision = static_cast<int>(SuperResInferencePrecision::Fp16);
    params.commandBatchSize = 2;
    if (!should_request_optimized_vulkan_tta(params, true, true) ||
        should_request_optimized_vulkan_tta(params, false, true) ||
        should_request_optimized_vulkan_tta(params, true, false) ||
        !should_run_safe_tta_directly(params, false, true) ||
        !should_run_safe_tta_directly(params, true, false) ||
        should_run_safe_tta_directly(params, true, true)) {
        return false;
    }

    const SuperResNativeParams safe = make_safe_tta_retry_params(params);
    return safe.tta &&
        safe.nativeBackend == static_cast<int>(SuperResNativeBackend::LegacySafe) &&
        safe.inferencePrecision == static_cast<int>(SuperResInferencePrecision::Fp32) &&
        safe.commandBatchSize == 1 && safe.retryCount == params.retryCount + 1 &&
        classify_optimized_tta_result(SuperResNativeCode::Ok) ==
            SuperResTtaRetryDisposition::ReturnResult &&
        classify_optimized_tta_result(SuperResNativeCode::Cancelled) ==
            SuperResTtaRetryDisposition::ReturnCancelled &&
        classify_optimized_tta_result(SuperResNativeCode::ModelMissing) ==
            SuperResTtaRetryDisposition::ReturnPermanentFailure &&
        classify_optimized_tta_result(SuperResNativeCode::TileOutputMismatch) ==
            SuperResTtaRetryDisposition::RetrySafeTta &&
        classify_optimized_tta_result(SuperResNativeCode::VulkanFailed) ==
            SuperResTtaRetryDisposition::RetrySafeTta;
}

bool safe_retry_executor_restarts_and_honors_cancellation_check() {
    SuperResNativeParams params{};
    params.tta = true;
    params.nativeBackend = static_cast<int>(SuperResNativeBackend::Optimized);
    params.inferencePrecision = static_cast<int>(SuperResInferencePrecision::Fp16);
    params.commandBatchSize = 2;
    params.retryCount = 4;

    std::vector<SuperResNativeParams> attempts;
    int fallbackCount = 0;
    const SuperResNativeCode recovered = run_superres_tta_attempts(
        params,
        true,
        true,
        [&attempts](const SuperResNativeParams& attemptParams) {
            attempts.push_back(attemptParams);
            return attempts.size() == 1u
                ? SuperResNativeCode::TileOutputMismatch
                : SuperResNativeCode::Ok;
        },
        [](SuperResNativeCode code) { return code; },
        []() { return false; },
        []() { return SuperResNativeCode::Cancelled; },
        [&fallbackCount](SuperResNativeCode) { ++fallbackCount; }
    );
    if (recovered != SuperResNativeCode::Ok || attempts.size() != 2u ||
        fallbackCount != 1 || attempts[0].nativeBackend != params.nativeBackend ||
        attempts[1].nativeBackend != static_cast<int>(SuperResNativeBackend::LegacySafe) ||
        attempts[1].inferencePrecision !=
            static_cast<int>(SuperResInferencePrecision::Fp32) ||
        attempts[1].commandBatchSize != 1 || attempts[1].retryCount != 5) {
        return false;
    }

    int cancelledAttempts = 0;
    fallbackCount = 0;
    const SuperResNativeCode cancelled = run_superres_tta_attempts(
        params,
        true,
        true,
        [&cancelledAttempts](const SuperResNativeParams&) {
            ++cancelledAttempts;
            return SuperResNativeCode::VulkanFailed;
        },
        [](SuperResNativeCode code) { return code; },
        []() { return true; },
        []() { return SuperResNativeCode::Cancelled; },
        [&fallbackCount](SuperResNativeCode) { ++fallbackCount; }
    );
    if (cancelled != SuperResNativeCode::Cancelled || cancelledAttempts != 1 ||
        fallbackCount != 0) {
        return false;
    }

    attempts.clear();
    const SuperResNativeCode directCpuSafe = run_superres_tta_attempts(
        params,
        false,
        true,
        [&attempts](const SuperResNativeParams& attemptParams) {
            attempts.push_back(attemptParams);
            return SuperResNativeCode::Ok;
        },
        [](SuperResNativeCode code) { return code; },
        []() { return false; },
        []() { return SuperResNativeCode::Cancelled; },
        [](SuperResNativeCode) {}
    );
    return directCpuSafe == SuperResNativeCode::Ok && attempts.size() == 1u &&
        attempts[0].nativeBackend == static_cast<int>(SuperResNativeBackend::LegacySafe) &&
        attempts[0].inferencePrecision == static_cast<int>(SuperResInferencePrecision::Fp32) &&
        attempts[0].commandBatchSize == 1 && attempts[0].retryCount == params.retryCount;
}

bool realcugan_postprocess_order_check() {
    constexpr auto order = superres_tta_output_order();
    return order == std::array<SuperResTtaOutputStep, 4>{
        SuperResTtaOutputStep::CanonicalFloatFusion,
        SuperResTtaOutputStep::CropWindow,
        SuperResTtaOutputStep::RealCuganResidual,
        SuperResTtaOutputStep::Rgb8Write
    };
}
} // namespace

bool run_superres_tta_micro_batch_runtime_checks() {
    return pass_group_plan_check() &&
        executor_preserves_group_and_fusion_order_check() &&
        executor_latches_cancellation_at_boundaries_check() &&
        executor_classifies_runtime_batch_downgrade_as_safe_retry_check() &&
        executor_stops_on_execution_and_fusion_failure_check() &&
        optimized_gate_and_safe_retry_classification_check() &&
        safe_retry_executor_restarts_and_honors_cancellation_check() &&
        realcugan_postprocess_order_check();
}
