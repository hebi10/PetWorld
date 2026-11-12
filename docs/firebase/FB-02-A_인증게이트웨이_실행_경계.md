# FB-02-A — 실제 Firebase 구성 기반 Google 인증 게이트웨이 준비

- 작성 기준: 2026-11-12 / FB-02 Google 로그인·비회원·계정 UI 기획
- 선행 PR: #27 FB-00 → #28 Rules → #29 firebaseDev → #30 고정 개발 서명 → **#31 Firebase dev/prod 환경 분리 및 실제 개발 APK 검증**
- 본 문서는 PR #31에 스택된 **개발 빌드 Google 인증 게이트웨이 추가** 작업을 기록한다.
- 운영 Play 앱 `com.haebi.petworld` / 기존 v0.1.0 (6) / main / Room·사진·암호화 백업 불변.

## 1. 기존 PR #31의 검증 확인

- [PR #31](https://github.com/hebi10/PetWorld/pull/31): `firebaseDev` / `release` Firebase 프로젝트·Android 패키지 분리, buildType별 Google Services 설정 경로 및 실제 개발용 고정 서명 워크플로 구현.
- [Android CI #43169816138](https://github.com/hebi10/PetWorld/actions/runs/43169816138): **success**, 개발·운영 가짜 설정으로 패키지·Firebase 리소스 격리 및 잘못된 프로젝트 차단.
- [실제 설정·고정 서명 통합 CI #43170850738](https://github.com/hebi10/PetWorld/actions/runs/43170850738): **success**. `firebase-dev-integration/fixed-fingerprint`가 보호된 키스토어를 복원하고 실제 개발 Firebase 구성을 빌드해 SHA(Gradle/keytool/apksigner) 일치 검증을 성공적으로 완료.
- PR #31의 소스와 GitHub Actions 성공은 직접 확인했다. Firebase Console의 현재 SHA 등록 상태는 사용자 전달 정보이며 직접 실시간 조회한 것은 아니다.
- PR #31은 Draft·미병합. 기존 `main`에 이 작업을 올려 Play 자동 배포를 일으키지 않는다.

## 2. 이 후속 PR에서 추가한 것

- `app/src/firebaseDev/AndroidManifest.xml`: Google 인증을 사용할 개발 변형에만 인터넷 권한 추가.
- `gradle/libs.versions.toml`: Credential Manager **1.6.0**·GoogleID **1.2.1** 버전 고정.
- `app/build.gradle.kts`: Firebase Auth 및 Credential Manager/Google ID 의존성을 **`firebaseDevImplementation`으로만** 구성. 기존 `releaseImplementation`과 운영 서명 설정 무변경.
- `app/src/firebaseDev/java/com/haebi/petworld/auth/FirebaseGoogleAuthGateway.kt`: 개발 변형의 Google ID 토큰을 Firebase Auth와 연결하기 위한 코드 준비.
- 명시적 Google 로그인 버튼(Credential Manager `GetSignInWithGoogleOption`)과 Google ID 토큰→`FirebaseAuth.signInWithCredential`; 로그에 ID 토큰·세션 비밀을 출력하지 않음.
- `com.haebi.petworld.dev` 및 실제 `petworld-dev` 프로젝트와 웹 OAuth 클라이언트 리소스가 모두 확인될 때만 로그인 요청 허용.
- 결과 유형은 SignedIn, Cancelled, Busy, MissingConfiguration, InvalidCredential, NetworkUnavailable, ProviderUnavailable, AuthenticationRejected. 중복 로그인 요청 차단.
- 사용자 계정 상태는 Firebase Auth SDK에 맡기고 토큰을 앱의 DataStore/Room에 별도 저장하지 않음.
- 기존 메인 UI/Room/클라우드 동기화와 연결하지 않았으므로, 해당 APK에 Google 로그인 버튼이 새로 나타나거나 실제 계정 로그인이 자동 실행되지 않음.

## 3. 검증·실서비스 경계

- 이 PR이 성공하면 Android CI의 `:app:assembleFirebaseDev`가 새 Credential Manager 게이트웨이를 포함해 빌드되는지 확인할 수 있다.
- **실제 Google 계정 로그인 성공은 별도 검증**: 개발 프로젝트에 등록된 SHA-1/256에 맞는 APK 설치, Google Play Services가 있는 기기, 실제 `petworld-dev`용 최신 설정, 계정 선택 UI와 로그인/로그아웃·재실행 테스트가 필요.
- FB-02-B에서 로그인 화면/비회원 사용·계정 설정을 연결하고 초기 로컬 자료·사진 유지, 네트워크 오류·취소·중복 클릭·재로그인 QA를 수행한다.
- FB-03 계정별 DB/사진/설정 분리 전에는 계정 간 로컬 기록 노출 방지 설계를 먼저 적용한다. 최초 서버 데이터 이전은 FB-04 범위다.
- 운영 Play 앱 서명 SHA-1은 별도 등록·검증 필요. 이번 개발용 키를 운영 Firebase에 등록하지 않는다.
- Firebase Console 비밀 설정이나 원격 Rules/과금 변경·Google Play 배포는 진행하지 않는다.

## 4. 준비 완료 판정

- PR #31 실제 고정 서명 APK 검증: **SUCCESS**, 기존 GitHub Actions 기록에 근거.
- 본 PR Google 인증 게이트웨이 코드·컴파일: 이 PR의 신규 Android CI 완료 후 판정.
- 사용자 조작이 필요한 실제 Google 계정 로그인·UID: **미검증**.
- 운영 Firebase 연결·Google Play 신규 배포: **미진행**.

Google 로그인 참고: https://firebase.google.com/docs/auth/android/google-signin
Credential Manager 공식 구현: https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation
개발 실행 결과 Drive: https://docs.google.com/document/d/1umWpj0FntZ5PTflpCrbw9OPhtHKFP5ku8Zo3Ge3SNg4/edit
