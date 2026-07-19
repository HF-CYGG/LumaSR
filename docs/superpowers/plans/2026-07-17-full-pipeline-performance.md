# LumaSR Full-Pipeline Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development with explicitly authorized parallel worktrees. Every production change follows superpowers:test-driven-development and receives task-level spec and quality review.

**Goal:** Implement seven guarded performance improvements while preserving output pixels, recovery behavior, fixed signing, and the current stable fallback paths.

**Architecture:** Work proceeds on `codex/performance-next` with isolated native-core, policy-calibration, and service-benchmark worktrees. Independent foundations run in parallel; changes to `superres_engine.cpp` are serialized through native-core and integrated in dependency order.

**Tech Stack:** Kotlin, coroutines, Android Bitmap/JNI, C++17, ncnn Vulkan, zlib streaming PNG, Gradle Android tests and Macrobenchmark.

## Global Constraints

- Do not add dependencies, storage permissions, signing material, model changes, or minSdk changes.
- Preserve the fixed Release signing certificate and use the existing fixed-signing scripts for final artifacts.
- Retain legacy raw-tile and PNG-transport paths as automatic fallbacks for one compatibility cycle.
- Do not claim physical-device or OEM performance without captured device evidence.
- Every task must show a failing test before production code, pass targeted validation, run `git diff --check`, commit independently, and pass task review.

---

### Task 1: Adopt owned native RGB images

- Add native input-image checks for ownership, deleter count, stride, overflow, Bitmap conversion and single decode across AUTO fallback.
- Add `SuperResRgbImage`, adopt the stbi pointer directly, and move the Bitmap RGB buffer into the engine without a second copy.
- Run native Debug/Release builds and processor JVM tests.
- Commit `Adopt native RGB buffers without duplicate copies`.

### Task 2: Preserve cache across thermal pauses

- Add processor and runner tests for stop reasons, user precedence, reason cleanup and warm retry behavior.
- Add `InferenceStopReason` and reason-aware cancellation while retaining the old overload for compatibility.
- Clear cache for user cancellation only; preserve it for thermal retry.
- Commit `Preserve native model cache across thermal pauses`.

### Task 3: Persist resolution-aware profiles

- Add repository and policy tests for resolution tiers, schema v3, old-record invalidation and missing output-pixel fallback.
- Add Vulkan/CPU calibrated profiles and persist nullable `outputPixels` in work specs.
- Commit `Persist resolution-aware inference profiles`.

### Task 4: Stream Extreme output

- Add planner tests for rectangular row bands, budgets, partial bands, ordering and overflow.
- Add native session checks for append order, retry, cancellation, atomic publication and pixel equivalence with the legacy merger.
- Implement `NativeExtremePngSession` and switch the active Extreme path; retain raw fallback.
- Commit `Stream extreme output without raw tile files`.

### Task 5: Use bounded RGB memory between stages

- Add estimator, pipeline transport and cleanup tests, including byte-for-byte decoded equivalence with PNG round-trip.
- Extract `RgbOutputTarget` and `run_ncnn_stage`; add memory, PNG and Extreme targets.
- Switch eligible chain/explicit pipelines to memory and record `memory` or `png-fallback` transport telemetry.
- Commit `Process multi-stage inference through bounded RGB memory`.

### Task 6: Overlap Vulkan and ordered CPU output

- Add slot state, backpressure, cancellation latch, error propagation and depth planner checks.
- Add telemetry fields and benchmark source assertions before changing execution.
- Implement producer/consumer depth 2 with independent allocators and ordered output; keep depth 1 fallback.
- Commit `Overlap Vulkan inference with ordered CPU output`.

### Task 7: Expand calibration

- Add tier boundary, candidate selection, thermal abort, timeout, rollback and CPU parameter tests.
- Search tile then batch per resolution tier; separately search CPU threads, blocktime and postprocess threads.
- Require a 5% improvement before selecting an aggressive candidate.
- Commit `Calibrate CPU and postprocess execution parameters`.

### Task 8: Batch optimized TTA safely

- Add safe-profile tests and native D4 micro-batch checks for shape, fusion equivalence, cancellation and quality gates.
- Add TTA-specific calibration; use two passes per command micro-batch only after quality eligibility.
- Fall back to LEGACY_SAFE/FP32/batch1/depth1 on failure or missing evidence.
- Commit `Batch TTA on optimized Vulkan safely`.

### Task 9: Benchmark and final verification

- Extend benchmark scenarios and schema with Extreme, multi-stage, TTA, pipeline depth, overlap/wait, peak slot and transport fields.
- Run the full JVM, lint, native ABI and benchmark build matrix.
- Build and verify the fixed-signed Release APK.
- If devices are attached, run cold/warm benchmarks and stress/cancel/thermal scenarios; otherwise record the missing physical-device gate without making speed claims.

