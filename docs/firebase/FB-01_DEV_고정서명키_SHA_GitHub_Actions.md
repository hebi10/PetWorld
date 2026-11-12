# FB-01 — 클라우드 고정 개발용 서명키·SHA 점검

- 작성일: 2026-11-12
- 범위: GitHub Actions와 전용 개발 서명키의 분리, 고정 인증서 지문 검증, 안전한 1회 키 보관 절차
- 선행: PR #27 → PR #28 → PR #29 `firebaseDev` 빌드 추가
- 변경 대상: `com.haebi.petworld.dev` 개발 패키지만
- 운영 앱: `com.haebi.petworld` / Google Play `0.1.0 (6)` 변경 없음
- **주의**: GitHub PR의 정적 코드/임시 키 검증 통과와 Firebase에 등록 가능한 영구 SHA 발급은 서로 다름.

## 1. 목표 및 범위

해비님 PC의 `debug.keystore`를 요구하지 않고, GitHub 클라우드에서 **한 번만 생성하여 보호된 GitHub Environment의 Secret에 저장한 전용 개발용 키**로 `firebaseDev` APK를 서명한다. 이 APK의 인증서 SHA-1·SHA-256을 검증해서 `petworld-dev` Firebase Android 앱(`com.haebi.petworld.dev`)에 등록할 수 있다.

**코드만으로 GitHub 비밀값을 생성해 영구 저장할 권한은 제공되지 않음.** 저장소 소유자 또는 승인된 관리자 권한의 Codespaces/클라우드 터미널에서 한 번 아래 안전한 초기화 작업이 필요하다. 프라이빗 서명키를 Git에 커밋하거나 PR/Actions 아티팩트/채팅에 노출하지 않는다.

## 2. 구현

1. `app/build.gradle.kts`: `firebaseDev` 전용 `firebaseDevFixed` signingConfig, `PETWORLD_DEV_KEYSTORE_PATH`, `PETWORLD_DEV_STORE_PASSWORD`, `PETWORLD_DEV_KEY_ALIAS`, `PETWORLD_DEV_KEY_PASSWORD` 환경 값 사용.
2. `PETWORLD_DEV_REQUIRE_STABLE_SIGNING=true`일 때 서명키 4개 값이 불완전하면 빌드를 **중단**. 일부 값만 입력된 경우에도 기본 임시 디버그 키로 은근히 돌아가지 않음.
3. `.github/workflows/firebase-dev-signing.yml`: PR 이벤트에서는 가상 Firebase 설정으로 `:app:signingReport`를 실행해 **임시 CI 키**인지 검사하고, 그 값을 Firebase 등록용으로 표시하지 않음.
4. 위 워크플로를 **승인된 main에서 명시적 workflow_dispatch 실행**하는 경우에만 고정 서명키 사용 및 APK 생성. `petworld-dev-signing` GitHub Environment의 암호화 Secrets를 이용함. 이 단계는 아직 실행되지 않음.
5. `scripts/firebase/dev_signing_report.py`: Gradle signingReport, `keytool`, `apksigner` SHA-1/256이 모두 **동일**할 때만 공개 지문을 GitHub Actions Summary에 표시. SHA가 다르면 실패.
6. `scripts/test_fb01_dev_signing.py`: 파서 및 일치 검사 단위 테스트.
7. `scripts/firebase/provision_fixed_dev_signer.sh`: 안전한 GitHub Codespaces에서 생성된 PKCS12 개발용 키를 **바로 GitHub Environment Secrets로 전송**하는 1회 스크립트. 개인 키·암호를 표준 출력/PR에 출력하지 않고, 이미 등록된 키가 있으면 자동 회전 방지.

## 3. GitHub에서 한 번만 필요한 설정

환경 이름: `petworld-dev-signing`

- GitHub 저장소 > Settings > Environments > New environment에서 위 이름 생성.
- 가능한 경우 이 환경에 **필수 리뷰어** 및 허용 브랜치를 `main`으로 제한하는 보호 규칙 설정.
- `hebi10/PetWorld` 저장소에 접근 가능한 GitHub Codespaces 개발 환경에서 본 브랜치를 체크아웃하고, `gh auth status`로 로그인 계정 권한 확인.
- 아래 1회 명령으로 키 생성 및 해당 Environment의 네 가지 비밀값 저장.

```bash
bash scripts/firebase/provision_fixed_dev_signer.sh
```

저장되는 비밀값 이름:
- `PETWORLD_DEV_KEYSTORE_BASE64`
- `PETWORLD_DEV_STORE_PASSWORD`
- `PETWORLD_DEV_KEY_ALIAS`
- `PETWORLD_DEV_KEY_PASSWORD`

GitHub Actions에서 사용하는 이 키는 **운영 Play 업로드 키/Play 앱 서명 키와 별개**이며, 테스트용 `.dev` 앱에만 사용한다.

실제 초기화 스크립트 실행은 이 대화에서 직접 할 수 없다(관리자 권한으로 GitHub Secrets를 등록하는 커넥터가 제공되지 않음). 스크립트 실행 성공/Secrets 존재 여부도 확인 전에는 완료라고 보고하지 않는다.

## 4. CI 및 SHA 확인 순서

- PR 단계: `Firebase dev signing fingerprints` → `pr-preflight`. 가상 Firebase 설정에서 `:app:signingReport`를 실행하되 *임시 CI Runner debug 인증서 SHA를 노출하거나 Firebase에 등록하라고 안내하지 않음*.
- 키 1회 등록 후: 승인받은 기본 브랜치에서 `workflow_dispatch` → `fixed-fingerprint`. `keytool`이 보호된 키스토어를 읽고 `firebaseDev` APK를 서명·`apksigner`로 검증.
- 같은 job의 GitHub Actions Summary에 SHA-1·SHA-256 **공개 지문**이 출력되고 세 값 `Gradle = keytool = APK`가 일치할 때만 개발 Firebase 앱에 등록.
- GitHub Workflows의 `workflow_dispatch`는 **해당 YAML이 기본 브랜치에 안전하게 병합되어 있어야 실행 가능**. PetWorld는 현재 `main` CI 성공 시 Play internal 자동 배포될 수 있으므로 **PR을 바로 main에 병합하지 않는다**. 병합/Play 가드 검증은 별도 명시적 승인 및 릴리스 절차 후 진행.
- 영구 키를 첫 생성한 Codespaces 스크립트는 저장 완료 후 공개 SHA-1·SHA-256을 표시하므로, 승인된 환경에서 즉시 확인 가능. 이는 GitHub Actions의 실제 APK 검증을 대체하지 않음.
- 인증서의 공개 SHA를 Firebase Console에 등록하는 것은 원격 설정 변경이다. 값을 확인하기 전 임의 등록 금지.

## 5. 로컬 기기와 CI 키의 차이

- Windows의 기존 `:app:signingReport` (`Variant: debug`)는 **Windows 개인 debug.keystore** SHA를 출력한다.
- GitHub PR의 기본 `debug`는 **일시적인 CI Runner 키**이며 실행 간 고정됐다고 볼 수 없다.
- 이번 설계의 `firebaseDevFixed`는 **별도 환경 비밀값에 저장한 개발 전용 영구 키**이며, 위 둘과 다른 지문이 나오는 것이 정상이다.
- 개발 앱을 해비님 Windows에서도 동일 인증서로 설치하려면 별도 안전한 키 배포가 필요하다. 클라우드에서 빌드한 APK만 테스트하면 로컬 키와 일치시킬 필요는 없다.
- 운영 SHA-1은 별도 Play Console 앱 서명 인증서에서 확보. **개발 SHA/운영 업로드 키를 대체 등록하지 않는다**.

## 6. 남은 승인/검증 항목

[ ] GitHub `petworld-dev-signing` 환경 생성 및 보호 규칙 확인
[ ] 전용 개발 키 1회 생성, 비밀값 4개 암호화 저장 및 공개 SHA 확보
[ ] 승인된 main 소스에서 `workflow_dispatch` 구동 및 Gradle/키스토어/실제 APK SHA 일치 검증
[ ] Firebase `petworld-dev` Android 앱 `com.haebi.petworld.dev`에 위 개발 SHA-1/256 등록
[ ] 등록 후 최신 개발용 `google-services.json` 반영·Firebase 인증 실제 테스트
[ ] 운영 Firebase `petworld-prod-3b5c5`의 Play **앱 서명 SHA-1** 별도 등록
[ ] 기존 Play 0.1.0(6) 및 Room·암호화 백업 불변 검증

## 7. 보안 및 롤백

- 개발 서명키는 GitHub Environment Secrets에만 저장, Artifact/로그/앱/Drive/PR에 업로드 금지.
- CI job은 관리자 키·운영 서명키를 읽지 않으며 `contents: read` 권한만 요청한다.
- 인증서 재발급·회전은 개발 Firebase 등록 SHA와 기존 설치된 `.dev` APK의 업데이트 호환성에 영향을 준다. 자동 재생성 금지.
- Play 릴리스는 기존 키/워크플로를 사용하며 `firebaseDevFixed`는 어떠한 release 변형에도 적용되지 않는다.
- 키 설정 실패·지문 불일치 시 즉시 실패하고 고정 SHA를 표시하지 않는다.

## 8. 링크

- PR #29: https://github.com/hebi10/PetWorld/pull/29
- CI #43146457171: https://github.com/hebi10/PetWorld/actions/runs/43146457171
- Firebase 개발 프로젝트: https://console.firebase.google.com/project/petworld-dev/overview
- Firebase 인증 지문 등록: https://firebase.google.com/docs/auth/android/google-signin
- GitHub Actions Secrets: https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions
- Google Play App Signing: https://support.google.com/googleplay/android-developer/answer/9842756
