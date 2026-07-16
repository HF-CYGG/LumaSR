# Fixed Release Signing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task. Repository instructions forbid subagents unless the user explicitly authorizes them.

**Goal:** Make every locally distributed LumaSR Release APK use the existing certificate SHA-256 `43648398DD74C72B1CBA185D3832E558C72D9BB58AE46BB9C09EF32818988D2F`, preserving upgrade compatibility with previously installed releases.

**Architecture:** Keep the existing ignored self-distribution private key unchanged and do not duplicate it. A repository build script loads only that fixed key, checks its certificate before Gradle runs, and verifies the resulting APK certificate before publishing the artifact.

**Tech Stack:** PowerShell 5.1, Gradle/AGP, Java `keytool`, Android SDK `apksigner` and `zipalign`.

## Global Constraints

- Never generate or rotate a signing key.
- Never expose passwords or private-key contents in output or tracked files.
- Never deliver an APK whose certificate differs from the fixed SHA-256 fingerprint.
- Preserve the existing installed app and its data; do not uninstall it.
- Do not add dependencies or change Android application code.

---

### Task 1: Add a signing fingerprint regression gate

**Files:**
- Create: `scripts/verify-fixed-release-signing.ps1`

**Interfaces:**
- Consumes: `-ApkPath` or `-KeystorePath`, plus the existing signing environment for keystore inspection.
- Produces: exit code `0` only when the certificate matches the fixed SHA-256 fingerprint.

- [x] **Step 1: Write the verifier before changing the build path**

The verifier must normalize colons/case, use `apksigner verify --print-certs` for APKs, use `keytool -list -v` for keystores, and throw on a missing or mismatched fingerprint.

- [x] **Step 2: Verify RED against the newly generated incompatible APK**

Run:

```powershell
.\scripts\verify-fixed-release-signing.ps1 -ApkPath .\artifacts\releases\LumaSR-0.2.1-main-eb6df46-arm64-v8a-release.apk
```

Expected: non-zero exit with expected `4364...8D2F`, actual `F1AE...29EF`.

### Task 2: Add the canonical fixed-signing Release build

**Files:**
- Create: `scripts/build-fixed-release.ps1`
- Reuse without modification: `artifacts/signing/lumasr-selfdist.jks`
- Reuse without modification: `artifacts/signing/lumasr-selfdist-signing.env`

**Interfaces:**
- Consumes: the preserved self-distribution JKS and existing ignored signing environment file.
- Produces: `artifacts/releases/LumaSR-0.2.1-release-arm64-v8a.apk` signed by the fixed certificate.

- [x] **Step 1: Validate the existing matching key without modifying it**

Resolve the existing JKS from the ignored environment file and verify its fingerprint before using it. Never copy, migrate, overwrite, or regenerate the key.

- [x] **Step 2: Implement the minimal fixed build entrypoint**

The script must set all four `LUMASR_UPLOAD_*` variables only for the child build, run `testDebugUnitTest lintDebug assembleRelease`, verify the output certificate, copy the APK to `artifacts/releases`, and restore the caller's environment in `finally`.

- [x] **Step 3: Verify GREEN**

Run the fixed build script, then run the verifier against both the old APK and new APK. Expected: both report the exact fixed certificate fingerprint.

### Task 3: Final release validation

**Files:**
- Verify: `artifacts/releases/LumaSR-0.2.1-main-eb6df46-arm64-v8a-release.apk`

- [x] **Step 1: Run artifact checks**

```powershell
apksigner verify --verbose --print-certs <apk>
zipalign -c 4 <apk>
```

Expected: APK verifies, is aligned, uses one RSA signer, and has SHA-256 certificate fingerprint `43648398DD74C72B1CBA185D3832E558C72D9BB58AE46BB9C09EF32818988D2F`.

- [x] **Step 2: Confirm repository state**

Run `git diff --check` and `git status --short --branch`. Only the signing policy plan and scripts may be tracked changes; private signing files and APK artifacts must remain ignored.
