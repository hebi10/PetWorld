# FB-07-C2-C2-03B-1 — 계정별 SDK 작업 종료 추적 및 캐시 보존

선행 #77 a51fb435b2f73e03c05ed35c1c7eed95091decb8. 실제 삭제·서버/웹/Play 배포 없음.

- AccountCloudWorkTracker: 계정별 SDK Task, 동기화 worker, 최초 보관, 충돌 검토의 시작과 종료를 동일 process-local gate에서 추적한다. 차단과 작업 등록은 동일 monitor, permit 해제는 idempotent. 다른 계정의 작업은 영향받지 않는다. 계정별 blockNewWork 및 exact-account sync lease revoke 이후 bounded awaitDrained를 제공한다. 현재 실제 삭제 UI에서는 호출하지 않는다.
- FirebaseDevCloudWork: Firebase Task는 등록 이후 lazy start하고 실제 SDK 완료 callback에서만 permit을 해제한다. Coroutine cancel/WorkManager cancel은 작업 완료 근거가 아니다. Storage 업로드에는 취소 요청을 전달하지만 Task 완료를 계속 기다린다. Task 생성 시점의 불명확한 예외·callback 미등록은 안전하게 미확인 상태로 남긴다.
- 적용 대상: FirebaseDevRemoteReader, FirebaseDevSyncTransport, FirebaseDevPhotoStorage, FirebaseDevFirstImportService, FirebaseDevSyncWork, FirebaseDevSyncStatusSection. 기존 계정 검증 및 journal/권한 gate, 원본 보존, 요청 계약을 유지한다. 최초 보관 트랜잭션 retry에서도 대상 계정 확인. 새 요청 차단 후 이전 요청의 완료를 추적한다.
- 테스트: 단위 6개(계정 격리, 네 종류 종료, timeout/cancel, 중복 해제, 프로세스 재시작 미확인, 동시 등록/차단), Android FirebaseDev 계정 smoke에 실제 Google TaskCompletionSource 합성 회귀 2개(취소 후 완료 callback까지 미종료, 차단된 Task factory 미호출) 추가.
- 중요: process-local drain은 SDK 내부 persistence나 이전 프로세스 작업, 로그인 인증 SDK 전체, UI/ViewModel/이미지/Room lifecycle의 종료까지 증명하지 않는다. FirebaseDevDeletionCachePolicy의 UNVERIFIED_SHARED 차단 유지. clearPersistence 및 파일 일괄 삭제, 기존 SDK 캐시 설정 전환 없음.
- 03B-2: 계정별 캐시 분리 방식과 기존 설치에서 미전송 쓰기 및 캐시 보존을 검증해야 한다. Firebase Android SDK는 memory cache 설정을 지원하지만 기존 공유 디스크 캐시를 초기화하거나 terminate만 호출해도 미전송 쓰기 보존/제거를 증명할 수 없다. 새 설치와 기존 설치를 구별하는 검증 가능한 migration, SDK 내부 작업의 종료, A/B/Guest 계정별 테스트, 인증된 복구 UI 및 슬롯 해제 정책 필요. 실제 클라우드 삭제 기능은 모두 검증되기 전 비활성 유지.
- 공식 참조: https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/FirebaseFirestore ; https://firebase.google.com/docs/firestore/manage-data/enable-offline
