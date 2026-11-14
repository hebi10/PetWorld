# FB-04-FINAL — 최초 Firebase 이행 충돌·재시도 QA

- 작성: 2026-11-12 / PetWorld / Draft PR #37
- 선행 PR #36: `feature/fb-04-opt-in-first-import`
- 목표: 사용자 승인 없는 업로드 금지, 동일 UID·동일 원본·동일 클라우드 상태에 한해서만 최초 이행
- **배포 동결: FB-09 전체 통합 승인 전 `main` 병합·Play 업로드 금지**

## 1. 실 서버 테스트와 Emulator 테스트의 구분

현재 ChatGPT의 GitHub 앱은 Android 테스트용 Google 인증 세션, 개발용 고정 서명 Secret 및 실제 기기 UI를 가져오지 못합니다. 연결된 Browser 세션도 접근되지 않았습니다. 이에 따라 실제 `petworld-dev` 프로젝트의 사용자 인증·Firestore/Storage 최초 업로드를 실행하거나 검증했다고 보고하지 않습니다.

이번 PR의 Firebase Rules CI는 반드시 `demo-petworld-fb01`에만 연결합니다. Rules SDK와 Firestore/Storage Emulators에서 write/retry 규칙을 검증하더라도 실제 petworld-dev 서버와 별개입니다.

### 실제 서버 PASS 증거 필수
1. **정확한 PR HEAD SHA** 및 최신 dev Google 설정으로 Android `firebaseDev` APK 빌드 후 등록된 고정 개발키로 서명, Gradle·keytool·APK SHA1/SHA256 일치
2. Google Play Services 테스트 기기에서 사용자 승인으로 로그인·Firebase token 온라인 갱신·계정 UID 일치 확인
3. 테스트 전용 새 계정의 server-side 최초 Firestore/Storage 상태(테스트 계정/기존 자료 없음을 승인 하에 조회)
4. 미리보기: 원격 write 없음, Guest 데이터 원본과 JPG SHA 인벤토리 확인, .petworld 암호화 백업 및 사용자 체크/최종 버튼 명시 실행
5. 0/1/1000건 및 사진 포함 최초 업로드 뒤 서버 강제 재조회, UUID·수치/NULL과 0·타임존·JPG 원본/원격 SHA 일치
6. 서버에 같은 UUID의 다른 내용·미리보기 이후 신규 데이터·계정 전환 A→B가 있을 때 쓰기 시작 전 충돌로 거부
7. 네트워크/프로세스 중단 뒤 같은 UID·같은 원본으로 재시도 시 동일 페이로드 중복 생성 없음. 필요 항목만 이어 쓰며 ready 표시는 전부 검증된 후만
8. 종료 후 Guest DB·사진/선택프로필·암호화 백업 원본 불변, 다른 Firebase UID의 내용은 읽거나 덮어쓸 수 없음

**현행 Storage/Firestore Rules는 기존 사진·문서 delete를 거부합니다.** 실서버 테스트에서 만든 임시 데이터도 자동 정리할 수 없으므로 테스트용 계정/프로젝트와 데이터 보유·정리 방법을 사전에 분리해야 합니다. 승인 없는 실제 계정/기존 사용자 기록 쓰기는 금지입니다.

## 2. 이번 코드 변경

### 계정·미리보기 승인 바인딩
- `FirstImportPreview.accountFingerprint`에 `SHA-256(firebaseUid)`를 저장하고 `commit()`에서 현재 Firebase UID와 일치 확인
- `FirebaseAuth.currentUser.getIdToken(true)`로 실제 온라인 토큰 재검증 및 UID 동일성 확인 후 쓰기 시작
- Auth providerData에 `google.com`이 있는 비익명 Google 계정만 허용
- `FirstImportGuards`: 승인 Guest fingerprint/동일 클라우드 문서 개수/extra/충돌 개수를 새로 읽은 결과와 비교. 미리보기 이후 출처·계정·서버 상태가 달라지면 재승인
- 네트워크 중단 시 기존 동일 UUID + 값은 재사용, 상이한 문서나 JPG bytes는 덮어쓰지 않음

### 사진 SHA 증거와 metadata 호환
- Storage Rules의 허용 필드·create-only·JPG 5MiB 정책 변경 없음
- Firebase Emulator에서 Custom Metadata 포함 신규 샘플 업로드가 권한 거부로 실패해 테스트·실제 업로드 구현을 **필수 custom metadata에 의존하지 않는 경로**로 수정
- SHA-256은 앱 로컬 JPG에서 계산하고, 서버로 보관된 **실제 JPEG bytes를 재다운로드**해 계산한 해시·바이트 수를 직접 대조
- 기존 원격 사진에 sha256 customMetadata가 있을 때만 추가로 비교하므로, 기존 객체 검증과의 역호환 유지

## 3. 자동 테스트

- `app/src/test/java/com/haebi/petworld/data/migration/FirstImportGuardsTest.kt`: 계정 A·B 변경·소스 fingerprint 불일치·서버 자료 추가/충돌 시 거부, 승인 체크 1,000회 결정성
- `firebase/test/rules.test.mjs` Emulator 통합:
  - 프로필까지만 보관된 시점에서 중단 → 재시도 시 같은 프로필 skip·누락된 기록 생성 → 서버 readback 후 ready
  - 동일 UUID이지만 다른 내용 → 에러/원격 원본 보존/ready 미표기
  - Storage 원격 JPG byte SHA 확인, 다른 UID 읽기/덮어쓰기 거부
  - Guest 빈 원본 → 원격 user/ready 문서 미생성
- 이전 PR #36 기존 테스트: 0/1/1000건 매핑·JPG·time zone·NULL/0 보존 및 실서버 쓰기와 독립
- Android 계정 UI QA, PR 빌드, Guest 백업·복원, Room A/B 29·35·36 계측, Play source guard 유지

## 4. 최종 보고 구분

- Android CI PASS: 코드와 합성 config 빌드 가능하다는 의미
- Firebase Rules Emulator PASS: SDK+Emulator에서 접근·create/retry/conflict 동작 검증, **실 petworld-dev 인증·Server QA 아님**
- Firebase 계정 UI smoke PASS: 로그인 전 화면·Guest 안내 확인, **실 Google 계정 인증 성공 아님**
- 실제 Server QA PASS: 별도 Google 계정·최신 fixed signer APK·server readback 증거로만 판정
- 배포 완료: FB-09 전체 통합/승인 후 실제 Google Play API 반영 시점만 인정

## 5. 릴리스 제약

- PR #37은 PR #36에 스택한 Draft, 아직 main에 병합하지 않음
- Play 자동 배포가 켜진 main에 임시 올리거나 환경 Secret을 pull_request 워크플로로 우회하지 않음
- Firestore/Storage 실제 프로젝트 Rules·사용자 데이터·요금제·Google Play 패키지/서명·테스터 트랙 불변
- 사용자에게 결과는 CI 링크·미완료 실서버 접근 조건을 따로 제시
