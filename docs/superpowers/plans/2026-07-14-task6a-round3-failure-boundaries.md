# Task6A Round3 Failure Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `test-driven-development` and execute this plan inline. The user explicitly forbids subagents and commits for this task.

**Goal:** Prevent recoverable coordinator and foreground-service coroutine failures from reaching root uncaught handlers while preserving persisted recovery and cancellation semantics.

**Architecture:** `ProcessingCoordinator` will terminate a failed worker normally after a `NonCancellable`, mutex-protected recovery checkpoint; only cancellation of the worker/parent remains cancellation. A small pure service failure boundary will be invoked by both Service root launches, emit stable failure codes only, and always run lifecycle finalization.

**Tech Stack:** Kotlin, kotlinx.coroutines, Android Service, JUnit 4.

## Global Constraints

- Modify only Task6A domain/service code and JVM tests; do not touch native, UI, Task7, Gradle, or assets.
- Add no dependency, do not commit, and do not use subagents.
- Use strict RED→GREEN for each behavior and preserve all round1/round2 tests.
- Never log task IDs, file paths, image metadata, exception messages, or exception stack traces from the Service boundary.
- Recovery persistence must happen before publishing QUEUED; a second persistence failure must not escape or create an automatic retry loop.

---

### Task 1: Recover worker failures before RUNNING without a root exception

**Files:**
- Modify: `app/src/test/java/com/lumasr/domain/ProcessingCoordinatorTest.kt`
- Modify: `app/src/main/java/com/lumasr/domain/ProcessingCoordinator.kt`

**Interfaces:**
- Consumes: existing `ProcessingCoordinator.resumePersistedWork()` and `TaskRecoveryPolicy`.
- Produces: a worker Job that completes normally after recoverable failure and is rebuilt only by explicit resume.

- [ ] **Step 1: Write the failing test**

Add a repository that throws `IOException` once from `ProcessingWorkSpecRepository.find`, then assert the stopped worker Job is completed but not cancelled, the task remains recoverable, and one explicit resume executes it exactly once.

- [ ] **Step 2: Run test to verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.lumasr.domain.ProcessingCoordinatorTest.workSpecFailureStopsWorkerNormallyUntilExplicitResume --console=plain
```

Expected: failure because the current worker Job is exceptionally cancelled and the exception reaches the root handler.

- [ ] **Step 3: Implement the minimum boundary**

Change `consumeQueue` to rethrow only when `currentCoroutineContext().isActive` is false. For an active-context failure, execute recovery in `withContext(NonCancellable)`, clear the queue reservation, and return so the worker ends normally.

- [ ] **Step 4: Run the focused test for GREEN**

Run the same command and require exit code 0 with exactly one resumed execution.

### Task 2: Recover persisted or in-memory RUNNING safely

**Files:**
- Modify: `app/src/test/java/com/lumasr/domain/ProcessingCoordinatorTest.kt`
- Modify: `app/src/main/java/com/lumasr/domain/ProcessingCoordinator.kt`

**Interfaces:**
- Consumes: `TaskRecoveryPolicy.recover(task, mode, atEpochMs)` and repository `find/save`.
- Produces: a persisted QUEUED checkpoint before snapshot publication, or removal of only the unchanged failed in-memory version when recovery persistence also fails.

- [ ] **Step 1: Write failing tests**

Add tests for:

```kotlin
runnerCancellationWithActiveParentRecoversRunningAndResumesOnce()
runningPersistenceFailureDoesNotEscapeRootOrPublishUnpersistedRecovery()
recoverySaveFailureDropsOnlyUnchangedFailedVersionAndWaitsForExplicitResume()
```

The cancellation test uses a runner that throws `CancellationException` while the coordinator scope remains active. The persistence tests inject `IOException` before/after RUNNING and during the recovery save, while recording persisted states and worker Job completion.

- [ ] **Step 2: Run each test for RED**

Run each method through `testDebugUnitTest --tests`; expect permanent RUNNING, exceptional worker completion, or an incorrectly published recovery.

- [ ] **Step 3: Implement state-aware recovery**

Inside `stateMutex`, compare the failed task/version with the latest in-memory and persisted state. Preserve a newer PAUSED, CANCELLED, or terminal state. Recover a confirmed RUNNING state with `TaskRecoveryPolicy`, save QUEUED first, then update `tasksById` and snapshots. If recovery read/save fails, remove memory and snapshot only when the same failed state/version is still current; always clear matching `activeExecution`, reservation, explicit-cancel marker, and progress throttle.

- [ ] **Step 4: Verify GREEN and cancellation semantics**

Run the three new tests plus existing pause/cancel tests. Require no automatic rerun before explicit resume and a maximum of one active runner.

### Task 3: Bound Service root launches and emit stable diagnostics

**Files:**
- Create: `app/src/main/java/com/lumasr/service/ProcessingServiceFailureBoundary.kt`
- Create: `app/src/test/java/com/lumasr/service/ProcessingServiceFailureBoundaryTest.kt`
- Modify: `app/src/main/java/com/lumasr/service/SuperResolutionService.kt`

**Interfaces:**
- Produces: `ProcessingServiceFailureCode`, a reporter receiving only that code, and a suspend boundary that catches non-cancellation failures and always runs a non-sensitive finalizer.
- Consumes: Service `launchOperation` blocks and `onTimeout` pause block.

- [ ] **Step 1: Write boundary RED tests**

Test that an `IOException` is contained, the reporter receives only `OPERATION_FAILED` or `TIMEOUT_FAILED`, the finalizer executes exactly once, and `CancellationException` is rethrown after finalization.

- [ ] **Step 2: Run test to verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.lumasr.service.ProcessingServiceFailureBoundaryTest --console=plain
```

Expected: compilation failure because the pure boundary does not exist.

- [ ] **Step 3: Implement and wire the boundary**

Create the pure boundary without Android dependencies. In `SuperResolutionService`, report `failureCode.stableCode` through `Log.e` without the throwable. Call the boundary from `launchOperation`, with lifecycle completion and idle-stop in its finalizer. Call it from `onTimeout`, with `stopSelfResult(startId)` in its finalizer.

- [ ] **Step 4: Run boundary and lifecycle tests for GREEN**

Run the boundary tests together with `ProcessingServiceLifecycleGateTest` and `ProcessingServiceClientTest`.

### Task 4: Final Task6A verification

**Files:**
- Verify only; update the round3 report in the parent-agent handoff.

- [ ] **Step 1: Run Task6A focused tests**

Run coordinator, work-spec repository, service client, lifecycle gate, and service failure-boundary suites. Require zero failures/errors.

- [ ] **Step 2: Run full JVM tests**

```powershell
.\gradlew.bat testDebugUnitTest --console=plain
```

Require exit code 0 and count tests/failures/errors from XML.

- [ ] **Step 3: Run Android lint**

```powershell
.\gradlew.bat lintDebug --console=plain
```

Require exit code 0.

- [ ] **Step 4: Inspect scope and report**

Run scoped `git diff --check`, confirm no native/UI/Task7 files were edited by this task, do not commit, and send the parent a round3 summary with RED→GREEN evidence.
