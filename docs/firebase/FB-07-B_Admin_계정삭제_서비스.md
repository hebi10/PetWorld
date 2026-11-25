# FB-07-B — 신뢰된 Firebase Admin 삭제 실행 기반

선행 #66 HEAD 5affb5ee41d2bf37167a1d00e2d1a7bfda7da0ff. 이 PR에는 실제 Firebase Admin SDK 호환 port 및 dev 전용 서비스 팩토리·요청/상태 처리 계약과 테스트를 추가한다. **아직 배포 가능한 Cloud Functions 진입점/queue 배선은 없으며 실제 서버 계정은 삭제하지 않는다.**

- POST 요청은 검증된 Google ID token (verifyIdToken(..., true)), 최근 5분 재인증, 명시적 확인으로 UID를 결정한다. body에 UID를 받지 않는다. 32-byte random base64url 상태 티켓의 SHA-256만 job에 저장. Auth 삭제 이후에도 job ID + 비공개 ticket로 최소 phase만 조회. 티켓/토큰을 URL·로그에 기록하지 않는다.
- accountDeletionLocks/{uid}와 accountDeletionJobs/{jobId}는 한 Admin transaction으로 생성. 기존 요청은 동일 ticket만 멱등 처리. lock은 유지하고 job lease는 20분, phase CAS와 token 확인. worker 실행시간은 lease보다 짧아야 하며 만료된 lease 재실행은 같은 잠긴 UID에 한해 멱등 수행. 이전 worker의 stale phase 변경 차단.
- Auth disable/revoke → Firestore recursiveDelete(users/{uid}) 및 자식 컬렉션 확인 → Storage 정확한 users/{uid}/ prefix와 generation 조건, 100개씩 제한 반복 → Auth 삭제 → 실제 잔존/보존 정책 검증. live·versioned·soft-deleted 객체를 모두 확인. soft delete 보존 기간 중 영구 완료로 표시하지 않는다. retentionAudit는 서버에서 검증된 실제 백업·보존 근거를 제공해야 하고 기본 완료 불가.
- 현재 팩토리는 petworld-dev project 및 해당 bucket, 관리자 설정/Rules lock/IAM/보존 검증 플래그가 모두 참일 때만 열림. 공개 endpoint와 scheduler 배포, 속도 제한/App Check, 실제 IAM·bucket 정책/백업 확인, 격리 테스트 계정 실제 검증, 앱·웹 사용자 화면/캐시 정리와 사용자 고지 등 후속 작업이 필요하다. 외부 HTTP에서 advanceTrusted를 호출할 수 있게 노출 금지.
- Unit test는 SDK 형태의 fake Auth/Firestore/Storage로 실행한다. 기존 Emulator Rules 테스트 유지. 실제 Firebase Admin emulator 연동/기기·서버 end-to-end 검증은 후속 FB-08 및 FB-07-C 완료 전 필요. main/Play/실서버 배포 없음.
- 공식 API: https://firebase.google.com/docs/reference/admin/node/firebase-admin.auth.baseauth ; https://googleapis.dev/nodejs/storage/latest/Bucket.html ; https://docs.cloud.google.com/storage/docs/soft-delete
