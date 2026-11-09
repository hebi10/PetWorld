# PetWorld Play Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** main 검증부터 서명 AAB의 내부 테스트 업로드까지 자동화합니다.

**Architecture:** 검증된 기존 앱의 표준 라이브러리 Publisher·서명 검사를 재사용하고 이 저장소·패키지·첫 버전에 고정합니다. exact-main 검증, 앱별 동시성, WIF와 실행 중 등록 바이너리 확인을 거쳐 제한된 트랙만 변경합니다.

**Tech Stack:** Kotlin/Gradle, GitHub Actions, Python unittest, Google Android Publisher API.

**Spec:** `docs/superpowers/specs/2026-11-09-play-actions.md`

## Global Constraints

- package `com.haebi.petworld`, repository `hebi10/PetWorld`, first version `0.1.0 (1)`.
- main-only google-play environment; internal default; alpha explicit; no production.
- Draft #5 remains unmerged. No user-facing features or new runtime dependencies.

## Review Focus

- Fork/PR/tag/old-main events must fail before signing or API writes.
- Empty inventories, stale versions, signer mismatch and partial readiness must fail closed.
- First commit fallback may omit only the exact forbidden review-hold parameter once.
- AAB and APK package/version/native contents must agree, including zero-native pairs.
- API success must not be described as actual installation verification.

### Task 1: Trusted source and Publisher

**Files:** `scripts/play/{source,preflight,publisher}.py`, `scripts/play/tests/`.
**Interfaces:** `source.resolve/verify_ci/release_mode`; `Publisher.inspect/initialize/deploy/promote`.

- [ ] Copy and adapt regression tests for this repository and code 1; confirm failures before scripts exist.
- [ ] Reuse audited existing scripts, pin repository/package/version and remove unrelated asset-sync markers.
- [ ] Run all Publisher/source/signing regressions.

### Task 2: Artifact and workflow integration

**Files:** `scripts/release_signing.py`, `scripts/play/{verify_artifacts,verify_native,prepare_bundletool}.py`, `.github/workflows/play-*.yml`, `app/build.gradle.kts`.
**Interfaces:** verified runtime readiness becomes `steps.readiness.outputs.initialized`; version allocation becomes `steps.version.outputs.version_code`.

- [ ] Add tests for package/version mismatch and paired zero-native artifacts, verify failures.
- [ ] Add release env signing/version overrides without changing debug builds; add workflows with source/gates/WIF/publish/readback.
- [ ] Run unittest, actionlint/YAML/diff checks; obtain independent review and PR Android CI.

### Task 3: Registration handoff

**Files:** `docs/privacy-policy.html`, `docs/play-store/registration.md`, `.github/workflows/privacy-pages.yml`.

- [ ] Document current-main local data handling and publish the public policy via Pages.
- [ ] Document exact manual modes, automatic main flow, required environment names and unverified Console/installation state.
- [ ] Create reviewable PR; root provisions dedicated signer/WIF and checks actual deployment after merge.
