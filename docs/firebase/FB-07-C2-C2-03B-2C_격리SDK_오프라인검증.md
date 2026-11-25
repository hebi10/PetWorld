# FB-07-C2-C2-03B-2C — 격리 Android SDK 인증·오프라인 쓰기 QA

선행 #80 수정 HEAD f02c6b8cf9d2bc5b4dc7a39573ff38e612790c3d. 기존 16671c21 계정 smoke 6건 중 2건 실패: named FirebaseApp 삭제 직후 Auth 내부 콜백에서 인스턴스 조회 실패 및 system foreground에서 계정 제목 탐색 실패. #80에서 합성 named app을 계측 프로세스 종료까지 유지하고, keyguard/statusbar 재확인 및 system foreground일 때 한 차례 Activity 재생성, 실패 창 진단을 추가했다. 기존 표시/계정 삭제 버튼 미노출 검증은 유지한다. 수정 HEAD의 CI 결과 확인 필요.

- 전용 Firebase Auth/Firestore Emulator Suite: 고정 demo-petworld-sdk-qa 프로젝트, 로컬 127.0.0.1:9099/8080, Android 10.0.2.2. 운영/실제 개발 Firebase 프로젝트에 접근하지 않는다. 테스트 전용 named FirebaseApp 및 합성 IDP 토큰으로 동일/다른 Google UID와 Guest를 검증하며 기본 FirebaseApp은 인증/fixture 데이터에 사용하지 않는다. 실제 운영 Google OAuth 또는 서버 App Check를 대체하지 않는다.
- 실제 Firestore Rules와 Android SDK: A/B 간 서버 읽기 거부, named memory cache의 다른 계정 자료 미노출, 같은 UID binding 및 Guest/다른 UID 차단, 미검증 production bootstrap 거부를 검증한다.
- 오프라인 쓰기: A named memory 및 B 별도 persistent client에 합성 업데이트를 대기시키고, A의 waitForPendingWrites timeout 동안 terminate/signOut 금지, 네트워크 재개 후 서버 확인 및 named SDK 종료. B persistent cache와 pending write 보존 및 이후 서버 반영, 기존 A legacy client 유지 검증. 전체 기존 설치의 단일 공유 캐시 내부 여러 사용자 mutation queue 대조를 의미하지 않는다.
- 전용 QA workflow는 API35, synthetic google-services fixture, local emulator host에만 HTTP가 허용되는 disposable runner manifest overlay를 사용한다. 실제 앱의 tracked manifest/서명/설정 변경 없음. Firebase CLI emulators:exec로 종료 관리. 두 계측 테스트가 실제 실행되어 통과했는지 XML로 확인(미실행/skipped를 성공 처리하지 않음). 일반 account smoke에는 명시적 local QA 인자가 없으므로 별도 local SDK test는 미실행.
- 실제 named FirebaseApp 삭제는 수행하지 않고 synthetic Auth signOut/Firestore terminate 후 프로세스 종료에 맡긴다. 실제 계정 삭제 또는 cache clearPersistence 없음. 새 PR의 CI 및 계측 결과는 아직 검증 전이다.
- 다음: #80 재검증 및 전용 local SDK QA 결과 확인. 실제 dev Google 인증·App Check·TLS/legacy migration 및 실제 PIN·Keystore, UI/Room/image/listener drain 및 복구 슬롯 해제는 별도 검증. C2-C2-04 격리 dev E2E, 외부 웹 삭제 및 FB-08 통합 QA, 최종 병합/배포 남음. 기존 UNVERIFIED_SHARED 실제 삭제 차단 유지.

공식 참고: https://firebase.google.com/docs/emulator-suite/connect_auth ; https://firebase.google.com/docs/emulator-suite/connect_firestore .
