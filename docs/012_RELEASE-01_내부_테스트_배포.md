# RELEASE-01 — PetWorld 최신 UI 내부 테스트 배포

- 기준일: 2026-11-11
- 저장소: `hebi10/PetWorld`
- 패키지: `com.haebi.petworld`
- 기존 검증 기준 `main`: `45edfdbd4e0d99748a2549cca519e61cb82afb37`
- 범위: 최신 UI-10-H-FINAL 코드와 기존 데이터 구조를 유지한 **Google Play 내부 테스트 트랙 업데이트만**
- 등록 대상: Play API `internal` 트랙, 상태 `completed`; 실제 게시·테스터 설치 여부는 별도 확인

## 사전 확인

- UI-10-A~H PR #17~#24 안전 병합 완료.
- 최종 Android CI: https://github.com/hebi10/PetWorld/actions/runs/42985261356 — 성공.
- 실제 Android API 35 전체 화면 캡처: https://github.com/hebi10/PetWorld/actions/runs/42985261344 — 성공.
- Google Play 이미지 8장 업로드 및 API 재조회: https://github.com/hebi10/PetWorld/actions/runs/42985261366 — 성공.
- 기존 내부 테스트 등록 이력: `versionCode 4`, 성공 https://github.com/hebi10/PetWorld/actions/runs/42785826429.
- 이 PR은 **앱 코드·Room 스키마·서명 설정·버전 설정·스토어 자산·테스터·closed 트랙을 수정하지 않음**.

## 승인된 실행 경로

1. 본 릴리스 메모 PR의 변경 범위와 Android CI/Play exact-source 검증을 확인한다.
2. `main`에 병합된 정확한 소스로 Android CI가 성공해야 한다.
3. 기존 `.github/workflows/play-internal.yml`의 정상 자동 배포 경로를 사용한다.
4. Play API의 실제 등록된 AAB/APK/트랙을 확인해 **기존 모든 versionCode보다 높은 새 버전**을 할당한다.
5. 기존 서명키로 AAB·APK 빌드 후 인증서 지문, 패키지명, 버전, 네이티브 16KB 호환성 검사를 통과해야 한다.
6. 오직 `internal` 트랙에 업로드하고 Play API Edit 커밋 및 별도 재조회가 성공한 경우에만 배포 완료로 기록한다.
7. Play Console의 심사·설치 가능 여부와 실제 물리 기기 QA는 별도 검증한다.

## 안전 제한

- **비공개 테스트 `alpha` 트랙 승격 금지**. 이번 릴리스 커밋 제목에는 `[closed-play-once]` 마커를 사용하지 않는다.
- `[initialize-play]`, `[petworld-ui10h-20261111]`, `[petworld-store04-20261111]` 등 기존 일회성 마커를 릴리스 커밋 제목에 사용하지 않는다.
- 기존 앱 ID, 등록 인증서, Play 환경 변수, 계정·비밀키는 교체·출력하지 않는다.
- Play API 오류/미확인 응답 시 새 업로드를 반복하지 않고 현재 등록 상태를 먼저 읽는다.

## 실행 결과

- 본 문서는 실행 전 승인 범위를 기록한다. GitHub Actions 최종 실행 ID, 할당된 versionCode, AAB SHA-256, 실제 Play API 응답은 **실행 후** 별도 검증 보고에 기록한다.
