# FB-01-CONNECT — Firebase 실서비스 접근·앱 등록·SHA·보안 규칙 배포 준비

- 작성일: 2026-11-12 (KST)
- 대상 저장소: `hebi10/PetWorld` / 기존 앱 `com.haebi.petworld`
- 선행: FB-00 PR #27(미병합) → FB-01 PR #28(미병합)
- 기존 Google Play 기준: `0.1.0 (versionCode 6)`, internal/alpha Play API 등록 성공. 콘솔 게시·실제 테스터 설치는 별도 검증
- 상태: **로컬 구성·보안 규칙·에뮬레이터 CI 완료, 실제 Firebase 콘솔 접근 및 서버 리소스 배포는 미확인·미실행**
- 관련 Google Drive 기획: https://docs.google.com/document/d/1LjtLD6otvDA40kmAONpfGPs3uofId9Y6LB0NdnZ9BhA/edit

## 1. 실제 접근 가능 여부 (확인과 미확인을 분리)

| 대상 | 확인 방법 | 이번 점검 결과 |
| --- | --- | --- |
| GitHub Firebase 코드 | PR #28 브랜치 직접 확인 | `firebase.json`, Firestore/Storage Rules, Emulator 테스트 존재 |
| Firebase 플러그인 | ChatGPT 플러그인 목록·사용 가능한 작업 확인 | 설치 항목 발견, 이 대화의 사용 가능한 도구에는 실제 프로젝트 조회/생성·Rules 배포 작업 없음 |
| 연결된 브라우저 | 사용자의 브라우저 탭 조회 시도 | 연결 오류로 Firebase Console 탭 조회 실패 |
| 실제 Firebase 프로젝트 목록 | Firebase 프로젝트 API/Console 또는 인증된 CLI | **미조회**; 생성됨/안 됨 여부 판단 불가 |
| Google Auth 공급자·Firebase Android 등록 | Firebase 프로젝트 접근 후 상세 조회 | **미확인** |
| Cloud Firestore DB·지역 | Firebase/Google Cloud 콘솔 또는 인증 CLI | **미확인** |
| Cloud Storage 버킷·요금제 | Firebase 콘솔의 Storage/Billing 상세 | **미확인**; 결제 연결 금지 |
| 서버 Rules 배포 이력 | 실제 Firebase 프로젝트 Rules 버전/배포 조회 | **미확인**; Emulator PASS는 원격 배포 증거가 아님 |

**중요:** Firebase 프로젝트가 존재하지 않는다고 단정하지 않는다. 프로젝트 ID, 계정·결제 정보, 인증서 SHA를 임의 생성하거나 다른 서비스의 값을 복사하지 않는다.

## 2. 환경 분리 확정 방향 (Firebase 공식 가이드에 근거)

| 환경 | 권장 표시 이름(프로젝트 ID 아님) | Android 앱 패키지 계획 | 사용 목적 | 상태 |
| --- | --- | --- | --- | --- |
| 개발·테스트 | PetWorld Dev | `com.haebi.petworld.dev` 후보 | 디버그·테스트 사용자·가상 데이터, Rules/로그인 QA | 미생성·미등록 |
| 운영 | PetWorld Prod | `com.haebi.petworld` **확정** | 현재 Play 내부/비공개 앱 및 향후 실사용 데이터 | 미조회·미등록 |

- Firebase는 개발과 배포 환경에 별도 프로젝트 사용을 권장한다. 테스트 데이터가 운영에 섞이지 않게 한다.
- 현재 앱 `app/build.gradle.kts`의 `applicationId`는 `com.haebi.petworld`이며, **현재 debug에도 별도 `.dev` 접미사 없음**. 개발 패키지 후보는 FB-02에서 `debug` 변형을 안전하게 추가·QA한 뒤에만 실제 등록한다. `release`의 기존 앱 ID는 절대 변경하지 않는다.
- Firebase 프로젝트의 **실제 고유 projectId**는 연결된 계정/Console에서 확인 후 기록. 위 표시 이름은 실제 생성·예약됐다는 의미가 아니다.
- 동일 Android 패키지명과 SHA-1의 조합이 다른 Google Cloud 프로젝트에서 중복 사용되지 않도록 점검한다.
- Firestore 데이터베이스 지역은 `asia-northeast3`(서울)을 우선 제안하되 **기존 DB 유무와 지역을 먼저 확인**한다. 기존 DB가 있으면 새로 생성하지 않는다.
- Storage 버킷 위치는 Firestore와 별개이며, 요금/데이터 전송·개인정보 처리 조건에 맞춰 선택한다.

## 3. 기존 프로젝트의 읽기 전용 인벤토리 절차 (실제 연결 후)

Firebase Console에서 접근 가능한 프로젝트를 확인하거나, 인증된 개발자 환경에서 다음 **조회 전용** 명령만 실행한다.

```bash
firebase login
firebase projects:list
firebase apps:list --project <DEV_PROJECT_ID>
firebase apps:list --project <PROD_PROJECT_ID>
firebase firestore:databases:list --project <DEV_PROJECT_ID>
firebase firestore:databases:list --project <PROD_PROJECT_ID>
```

- 명령의 꺾쇠 부분은 **콘솔에서 확인한 실제 ID로만 치환**. 알 수 없는 projectId를 생성하거나, 다른 서비스용 프로젝트에 무작정 Firebase를 추가하지 않는다.
- 이미 등록된 Android 앱이 있다면 Firebase Console > Project settings > Your apps에서 **패키지명**을 확인한다.
- 실제 Firebase Android App ID를 확인한 경우에만 SHA 목록을 읽는다.

```bash
firebase apps:android:sha:list <FIREBASE_ANDROID_APP_ID> --project <PROD_PROJECT_ID>
```

- 실제 프로젝트·앱 ID가 없으면 위 명령은 실행하지 않는다. 기존 프로젝트가 없다면 별도 Firebase Console 인증/환경 권한 확인 후 생성한다.
- 터미널/문서/스크린샷에 인증 토큰·비밀번호·서비스 계정 키를 기록하지 않는다.

## 4. Google 로그인 인증서 SHA 점검

**운영 앱:** Play Console > 앱 무결성(App integrity) > 앱 서명(App signing)의 **앱 서명 인증서 SHA-1 및 SHA-256**을 확인한다. 사용자 단말에 실제 설치되는 Play 서명 APK의 인증서이며, 업로드 키와 혼동하지 않는다.

**개발 앱:** 개발용 debug keystore의 SHA-1/256을 운영 값과 구분해 검증한다. 개발 변형의 `.dev` 패키지명과 일치하는 Firebase Android 앱 등록 후 별도로 추가한다.

- 운영 Google 로그인용 등록 입력: 실제 운영 Firebase 프로젝트의 Android 앱 `com.haebi.petworld` + Play 앱 서명 SHA-1(및 필요 시 SHA-256).
- 로컬/디버그: 개발 Firebase 프로젝트 + 실제 debug 빌드 서명 SHA + 확인된 패키지명.
- Firebase Auth에서 Google 로그인 공급자 활성화 및 사용자 공개 이메일·정책·지원 정보 검토.
- Google 로그인 공급자 활성화 후 최신 `google-services.json`을 해당 프로젝트/빌드 변형용으로 다시 내려받아 설정한다. 관리자 서비스 계정 JSON은 **절대 Android 앱에 넣지 않는다**.
- 저장소에는 현재 `google-services.json`이 무시되어 있으며, FB-02에서 개발·운영 환경별 파일/CI 보관 방법을 결정한다. 비밀키·서비스 계정·OAuth 클라이언트 시크릿은 로그나 저장소에 보관하지 않는다.
- 변경이 필요한 Android SDK·Google Services Gradle 플러그인·Credential Manager·인터넷 권한은 **FB-02** 단계에서 기존 Room/비회원 기능을 보존하면서 반영한다.

## 5. Rules 배포 준비 및 안전 게이트

현재 `firebase/firestore.rules`, `firebase/storage.rules`는 `demo-petworld-fb01` 에뮬레이터 테스트 10/10 PASS (PR #28). 이는 **실서버 Rules 미배포** 상태다.

배포 순서:
1. 대상 Firebase 개발 프로젝트 **ID·소유권·데이터베이스·버킷**을 확인한다. 실운영 프로젝트를 배포 대상에 지정하지 않는다.
2. 해당 프로젝트의 현재 Firestore/Storage Rules를 Console에서 열람·보존하고 차이를 검토한다.
3. GitHub PR #28의 정확한 HEAD를 고정하고 Emulator tests 10/10·Android CI·Play exact-source를 재확인한다.
4. 배포할 대상 Firebase 프로젝트 ID를 **명시적으로 지정**한다. `firebase use` 기본값에 의존하지 않는다.
5. Firestore Rules는 **개발 DB가 존재할 때만** 별도로 배포하고, Storage Rules는 **버킷과 Blaze 요금제 조건을 확인한 후** 별도로 배포한다.
6. CLI 배포는 Firebase Console의 기존 Rules를 덮어쓸 수 있다. 기존 정책 백업 및 승인 없는 배포 금지.
7. 배포 후 새 세션에서 보안 Rules 버전/적용 시각/본인 허용·미인증/타인 거부를 재검증한다. 배포 전후 리소스 변경과 실제 비용을 구분하여 기록한다.
8. 운영 프로젝트는 FB-07/08 보안·개인정보·계정 삭제·회귀 게이트를 충족하기 전 배포 금지.

**승인 및 환경 확인 이후 개발 프로젝트에만 사용할 수 있는 명령 예시**(이 문서 작성 시 실행하지 않음):

```bash
firebase deploy --only firestore:rules --project <VERIFIED_DEV_PROJECT_ID>
firebase deploy --only storage --project <VERIFIED_DEV_PROJECT_ID>
```

해당 명령은 **실제 원격 설정을 변경한다**. 개발 프로젝트라도 사용 승인·원격 기존 Rules 비교 및 해당 서비스 존재 확인 후에만 실행한다. 운영 프로젝트를 기본 대상으로 사용할 수 없도록 개별 체크가 필요하다.

## 6. 비용·보안·정책

- Cloud Storage for Firebase는 **Blaze 요금제가 필수**다. 결제 계정 연결 또는 요금제 변경은 사용자의 명시적 승인 없이는 수행하지 않는다.
- Blaze에서도 일부 무료 사용량이 있지만 버킷 지역·서비스별 상이하다. 미국 일부 리전 Always Free 조건을 서울 버킷에 적용한다고 가정하지 않는다.
- 예산 경보는 과금 자동 중지 기능이 아니다. 비용 경보·사용량 제한·중단 절차를 함께 설계한다.
- Firestore 기본 DB 위치는 생성 후 쉽게 바꿀 수 없다. 리전/요금/데이터 보관 정책 확정 후 생성한다.
- App Check/Play Integrity는 실 Android 앱 연결 및 테스트 계정·실기기 확인 전 강제 모드로 설정하지 않는다.
- 기존 개인정보처리방침의 로그인/서버 전송 없음 문구는 Firebase 데이터 전송이 실제로 시작되기 전 FB-07에서 개정해야 한다.
- 서버 클라우드 저장·Firebase 전송 암호화는 사용자가 별도의 키를 가진 E2EE와 다르다.

## 7. 연결·배포 완료 판정 조건

- [ ] 인증된 Firebase 콘솔/CLI에서 **실제 개발·운영 projectId**와 현재 프로젝트 소유권을 확인했다.
- [ ] 운영 `com.haebi.petworld` 및 개발 앱의 실제 패키지명·Firebase App ID가 확인됐다.
- [ ] 운영 Play 앱 서명 SHA-1/SHA-256과 개발 인증서 SHA가 정확히 등록됐다.
- [ ] Firebase Auth Google 공급자의 활성화와 필요한 OAuth/앱 구성 검증을 완료했다.
- [ ] Cloud Firestore 실제 DB의 위치·생성 여부·Rules 버전 확인.
- [ ] Storage Blaze/버킷 존재·위치·비용 위험을 확인하고, 별도 승인 후 작업했다.
- [ ] 개발 서버 Firestore·Storage Rules를 별도로 배포하고 실서버 readback 및 접근 권한 검증.
- [ ] 실제 Firebase 서비스에 연결된 Android 개발 빌드의 Google 로그인 성공/오류 QA.
- [ ] 운영 계정·서버·기존 Play 내부/비공개 테스트 AAB가 의도치 않게 변경되지 않았다.

현재 체크리스트 미완료는 **실제 프로젝트 접근/등록/서버 배포 정보가 확인되지 않았기 때문**이며, FB-01의 로컬 보안 테스트 실패로 해석하면 안 된다.

## 8. 다음 작업 — FB-02 착수 분리

- 인증 프로젝트 접근 없이도 **Google 로그인·비회원 UX 레이아웃/계정 상태 모델 및 테스트 픽스처**는 별도 브랜치로 선행 구현 가능.
- 그러나 실제 Firebase SDK 초기화·Google 계정 연동/서버 쓰기·실기기 로그인 **완료 판정**은 프로젝트/Android 앱/SHA/공급자 활성화 이후에만 가능.
- FB-02 단계에서도 게스트 Room DB, 로컬 사진, `.petworld` 백업, 앱 서명/Play 버전 불변. 최초 클라우드 데이터 이전은 FB-04 범위로 유지.

## 9. 참조 — 공식 및 프로젝트

- Firebase 프로젝트 분리: https://firebase.google.com/docs/projects/dev-workflows/general-best-practices
- Firebase CLI 조회: https://firebase.google.com/docs/cli
- Android Firebase 앱 등록: https://firebase.google.com/docs/android/setup
- Google 로그인: https://firebase.google.com/docs/auth/android/google-signin
- Play 앱 서명: https://support.google.com/googleplay/android-developer/answer/9842756
- Rules 배포·기존 Rules 덮어쓰기 주의: https://firebase.google.com/docs/rules/manage-deploy
- Firestore 지역: https://firebase.google.com/docs/firestore/locations
- Cloud Storage Blaze 조건: https://firebase.google.com/docs/storage/faqs-storage-changes-announced-sept-2024
