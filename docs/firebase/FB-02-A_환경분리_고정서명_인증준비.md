# FB-02-A — Firebase 환경 분리·고정 서명 APK 검증·Google 인증 준비

- 기준: 2026-11-12, Google Drive FB-02 기획 및 Draft PR #27 → #28 → #29 → #30
- 새 브랜치: `feature/fb-02a-firebase-config-auth-prep` (base: PR #30)
- **출시 보호**: 기존 PetWorld `com.haebi.petworld`, Room v2, `.petworld` 백업, Google Play 0.1.0 (6) internal/alpha 유지. `main` 병합 금지.
- **이 단계는 실제 Firebase 계정 로그인 성공이나 Play 배포 완료 판정이 아님**.

## 1. 확인한 기존 상태

- 개발 Firebase: `petworld-dev`, Android 패키지 `com.haebi.petworld.dev`; Google 공급자, Firebase 보안 규칙, 개발 인증서 SHA-1·SHA-256 등록은 사용자가 완료했다고 보고. 실서비스 Firebase 상태는 본 채팅에서 재조회/재검증 불가.
- 운영 Firebase: `petworld-prod-3b5c5`, Android `com.haebi.petworld`; 운영 Play 앱 서명 SHA-256 등록을 보고, **운영 SHA-1 미확인**.
- GitHub PR #29의 `firebaseDev`는 실제 개발 설정 파일이 있어야 추가되며 앱 ID 접미사 `.dev` 사용. PR #30은 고정 개발 서명 키스토어가 GitHub 환경 `petworld-dev-signing`의 4개 Secrets에 저장됐다고 사용자 보고.
- PR #30 전용 `fixed-fingerprint` 잡은 `main`에서 수동 실행한 경우만 보호된 키를 읽음. 일반 PR은 임시 GitHub Runner 서명을 사용하여 **Firebase에 등록 가능한 고정 키 지문을 증명하지 못함**.
- Drive/Library에 이전 `google-services (2).json` 개발 파일이 존재하나, 인증서 SHA 등록 후 최신 설정이라는 근거 없음. 파일을 소스에 복사하지 않음.

## 2. Android Firebase 구성 분리

| 환경 | Firebase 프로젝트 | Android 패키지 | 배치 위치 | 실행 정책 |
| --- | --- | --- | --- | --- |
| 개발 `firebaseDev` | `petworld-dev` | `com.haebi.petworld.dev` | `app/src/firebaseDev/google-services.json` | Credentials + Firebase Auth 전용, 인터넷 권한 dev 변형에만 |
| 운영 `release` | `petworld-prod-3b5c5` | `com.haebi.petworld` | `app/src/release/google-services.json` | 실제 운영 연결 명시적 승인 없이는 릴리스 빌드 차단 |
| 기존 `debug` | 미연결 | `com.haebi.petworld` | 구성파일 없음 | 기존 로컬 테스트 동작 유지 |

- 공통 경로 `app/google-services.json`, `app/src/main/google-services.json` 금지: 앱 변형 간 설정 혼입을 막음.
- `scripts/firebase/android_config_guard.py`는 최신 config의 projectId·패키지·Firebase App ID·웹 OAuth 클라이언트 존재를 **값 출력 없이** 검증. Demo CI만 `--ci-demo`에서 별도 projectId 허용.
- 실제 신규 config는 SHA 등록 후 Firebase Console의 *Your apps*에서 새로 내려받아 환경별 저장. `.gitignore`가 모든 `google-services.json`을 제외하므로 소스에 커밋하지 않음.
- Gradle은 개발 또는 운영 파일이 실제로 존재할 때만 Google Services 플러그인을 적용.
- `src/release/google-services.json`이 있고 `:app:assembleRelease`/`:app:bundleRelease` 등 운영 빌드를 요청하면, `PETWORLD_ENABLE_PRODUCTION_FIREBASE=true`의 **별도 명시적 값** 없이는 실패. 운영 정책/삭제/동기화 QA를 완료하기 전 승인하지 않음.
- 운영 파일이 없다면 기존 Play release 빌드·서명 설정 변경 없음.
- Google Services 플러그인은 buildType별 JSON을 지원한다. https://firebase.google.com/docs/android/google-services-plugin-and-file

## 3. Google 로그인 구현 준비(개발 변형에만)

- 안정 버전 Credential Manager 1.6.0 / Google ID 1.2.1 / Firebase BoM 35.1.0의 개발 전용 의존성을 적용하고 `src/firebaseDev/AndroidManifest.xml`에만 INTERNET 권한 선언.
- `src/firebaseDev/java/com/haebi/petworld/auth/FirebaseGoogleAuthGateway.kt` 준비: 명시적 Google 계정 선택 버튼용 Credential Manager → Google ID Token → FirebaseAuth `signInWithCredential`.
- 게이트웨이는 **현재 실행 패키지 `com.haebi.petworld.dev` 및 Firebase projectId `petworld-dev`**가 모두 확인되고, 최신 config에 `default_web_client_id`가 있을 때만 시작. Firebase ID Token·세션·자격증명을 로그에 남기지 않음.
- 인증 결과를 SignedIn / Cancelled / Busy / MissingConfiguration / InvalidCredential / NetworkUnavailable / ProviderUnavailable / AuthenticationRejected로 구분.
- 동시 로그인 요청은 중복 방지; Credential Manager 계정 취소/오류와 Firebase 인증 오류를 구분.
- 게이트웨이에는 Firebase 인증 상태만 존재. **아직 MainActivity/Compose UI·Room 저장소/클라우드 동기화와 연결하지 않음**, 계정 인증 자체가 데이터 동기화 완료가 아님.
- FB-02-B에서 로그인·비회원 선택 UI, 설정 계정 상태, 로그아웃, 오류 메시지, 프로필 보존 QA를 추가. FB-03 계정별 DB 분리 전에는 서로 다른 사용자의 로컬 데이터가 노출되지 않도록 UI에서 계정 전환/클라우드 접근을 주의 깊게 통제.

## 4. 고정 개발 인증서와 실제 APK 검증

- 고정 개발키용 Secrets 네 개(`PETWORLD_DEV_KEYSTORE_BASE64` 및 비밀번호·별칭·키 비밀번호)는 보호된 GitHub `petworld-dev-signing` 환경에 등록됐다는 사용자 보고가 있음. 이 채팅의 GitHub 연결은 Secrets 값을 조회하지 않으며 **비밀값을 출력하거나 원격으로 수정하지 않음**.
- 기존 PR #30 `.github/workflows/firebase-dev-signing.yml`을 보완: PR에서 임시 서명용 가상 `demo-petworld-fb01` 설정을 검증; 보호된 고정 서명 잡에서는 선택적으로 `PETWORLD_DEV_GOOGLE_SERVICES_JSON_BASE64`를 읽어 **진짜 최신 개발 Firebase 프로젝트 config**를 검증할 수 있음.
- 실제 최신 개발 config Secret이 없다면 보호된 잡은 **가상 config**를 사용하되 결과에 *SYNTHETIC* 표시. 가상 config에서 APK 서명을 검증해도 Google 로그인 테스트를 통과했다는 뜻이 아님.
- 고정 키 인증서 일치 판정은 Gradle signingReport SHA-1·SHA-256 = keytool SHA-1·SHA-256 = APK apksigner SHA-1·SHA-256일 때만 PASS. 코드만으로 고정 키의 실제 지문을 확인할 수 없음.
- 고정 잡은 `main`에서 승인된 `workflow_dispatch`에만 실행되므로, **Draft PR만으로 실제 고정 키 APK 검증은 불가**. GitHub Actions 버전/보호 환경 승인을 확인하고, Play 자동 업로드 차단이 확정된 안전 병합 절차가 필요.
- **이번 단계에서는 `main`·Play 워크플로·서명 키·비공개 테스트 설정을 변경하거나 앱을 배포하지 않음.**

## 5. CI/QA와 실제 연결 구분

- PR(Android CI): **가상 Firebase 개발·운영 설정**(실 Firebase 프로젝트가 아님)으로 앱 ID/리소스 혼입을 확인하고, 변경된 개발 Firebase Kotlin Gateway를 포함한 `assembleFirebaseDev` 컴파일 확인.
- `android_config_guard.py` Unit Tests: 프로젝트·앱 ID 불일치, OAuth 웹 클라이언트 누락, 잘못된 환경 config, 중복 클라이언트 등 거부.
- PR 고정서명 preflight: 가상 설정에서 개발 APK 빌드와 signingReport를 실행하되 임시 debug SHA는 Firebase 등록용으로 사용 금지.
- 별도 Android Lint/Unit, Firebase Rules Emulator, Play exact-source(소스/배포가드 검사일 뿐 Play 업로드 아님) 유지.
- 실제 Firebase 인증: 최신 config + Google 계정 선택 가능한 Android 실기기/에뮬레이터 + 검증된 개발 APK SHA + Google 인증 공급자 등록이 충족돼야 실행. **아직 미검증**.

## 6. 다음 작업: FB-02-B

1. Firebase Console에서 SHA 반영 후 내려받은 **개발 및 운영용 최신** `google-services.json`을 각 환경별로 준비해 안전한 Secret/빌드 경로에 배치.
2. GitHub `petworld-dev-signing` 환경에 개발 config를 전달할 경우 `PETWORLD_DEV_GOOGLE_SERVICES_JSON_BASE64` Secret을 별도 등록(기존 네 가지 서명 Secret과 구분).
3. Play 자동 배포로 이어지지 않는 승인된 고정서명 워크플로에서 실제 개발 APK의 cert SHA-1·SHA-256 동일성 확인.
4. 개발 기기에서 Google 로그인 버튼 → 계정 선택 → Firebase UID 생성/재사용 → 로그아웃·재로그인·취소·네트워크 오류 점검.
5. 기존 Guest Room 데이터/사진/암호화 백업 무변경 확인, Google 로그인과 원격 최초 데이터 이전(FB-04)은 분리.
6. 운영 Google 로그인 Play 서명 SHA-1 등록과 개인정보처리방침/Google Play Data Safety 수정은 실제 운영 출시 이전 필수.

참조: https://firebase.google.com/docs/auth/android/google-signin / https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation / https://firebase.google.com/docs/android/google-services-plugin-and-file
