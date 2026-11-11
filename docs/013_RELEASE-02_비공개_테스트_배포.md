# RELEASE-02 — PetWorld 비공개 테스트(alpha) 배포

- 승인: 2026-11-11 사용자 요청 "비공개 테스트까지 배포해줘"
- 앱: `com.haebi.petworld` / `hebi10/PetWorld`
- 배포 경계: **Google Play 비공개 테스트 `alpha` 트랙**까지, 프로덕션 출시 금지
- 기준 소스: `6cc0851d05d78d4c7dd9ce7baa1e117bab815d9f`
- UI-10-H-FINAL 최신 UI 유지. 앱 코드/Room/앱 ID/서명키/기존 스토어 이미지/테스터 설정/가격·설명 변경 없음.

## 선행 근거

1. UI-10-H-FINAL Android QA: `42985261356` 단위·Lint·Debug 성공, `42985261344` API35 실화면 82장 성공.
2. Google Play `ko-KR` 이미지: `42985261366` Play API 커밋·신규 재조회 성공.
3. RELEASE-01 `main` CI: `42990304928` 성공.
4. 내부 테스트 AAB 릴리스: `42990666697` 성공 — `0.1.0 (5)`, `internal/completed`, Play API 커밋·재조회 완료.
5. 이전의 `play-closed-once.yml` 자동 비공개 승격 성공 기록: `42786549117` (2026-11-10).

## 실행 방법과 버전 정책

현재 연결된 GitHub 기능은 `workflow_dispatch` 직접 실행을 지원하지 않습니다. 기존 `.github/workflows/play-closed-once.yml`가 검증한 원샷 경로를 사용합니다.

1. 이 문서만 포함한 Draft PR에서 Android CI 및 Play exact-source 테스트를 통과합니다.
2. 병합 커밋의 **제목에만** `[closed-play-once]` 마커를 단 한 번 넣어 `main`에 병합합니다.
3. `main` CI가 성공하면 기존 `play-internal.yml`이 새 정수 `versionCode`(등록된 모든 AAB/APK/트랙보다 큰 값)를 할당해 동일 앱 소스를 기존 서명으로 서명·검증한 AAB를 `internal`에 제출합니다.
4. `internal` 트랙의 `completed` 상태와 Play API 새 조회 결과가 성공한 경우에만 `play-closed-once.yml`가 **그 정확한 동일 `versionCode`**를 기존 `alpha` 트랙으로 승격합니다. 비공개 승격은 AAB를 다시 업로드하지 않습니다.
5. `alpha` 트랙 Play API 커밋 및 별도 편집 세션의 재조회가 확인될 때만 배포 성공으로 판정합니다. 실제 Play Console 심사·테스터 설치 가능 여부는 별도 확인합니다.

**주의:** 이미 등록된 `versionCode 5`를 그대로 승격하는 것이 아니라, GitHub 자동 승격 조건 때문에 새 릴리스 버전으로 내부 테스트를 갱신한 후 그 같은 버전을 비공개 테스트로 승격합니다. 실제 코드는 바뀌지 않습니다.

## 실패 대응 및 제한

- Gradle 빌드 실패, 버전 할당 충돌, 서명·AAB SHA·16KB 네이티브 호환성 실패, Android CI 실패 시 업로드/승격 중단.
- Play API 응답이 불명확하면 중복 커밋·중복 릴리스를 임의 반복하지 않고 GitHub 보고 아티팩트와 Play API 상태부터 확인합니다.
- `[closed-play-once]`는 원샷 자동화 마커이며 다른 릴리스에 반복 사용하지 않습니다.
- 비공개 테스트 테스터 명단, 국가, Play 개인정보·앱 콘텐츠·프로덕션, 계정·비밀키 및 서명 설정 수정 금지.
- Play API `alpha`의 `completed` 상태는 실제 콘솔 노출/테스터 설치 검증과 구분합니다.

## 실행 결과

실제 GitHub Actions Run ID, 내부/비공개 테스트 버전, AAB SHA-256, Google Play API 등록·재조회 결과는 해당 워크플로 완료 후 GitHub PR 검증 보고서에 기록합니다.
