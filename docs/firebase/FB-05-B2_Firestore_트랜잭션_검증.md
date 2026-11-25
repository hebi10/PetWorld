# FB-05-B2 — 개발용 Firestore 원자적 전송 연결

- 선행 Draft PR #57 HEAD 47725b51495b30787569ebebf9acec6ccec036de. FB-05-B1 재시도 상태 머신 및 #56 계정별 Outbox를 그대로 유지한다.
- FirebaseDev 전용 adapter에서 Google 계정 scope·개발 패키지·프로젝트를 검증하고 실제 온라인 ID 토큰 갱신 후에만 Firestore transaction을 요청한다. 기본 권한은 false이며 앱·스케줄러에서 호출하지 않는다.
- users/{uid}/syncReceipts/{opId}와 대상 문서를 동일 transaction에서 기록한다. 불변 receipt에는 작업/대상/예상 및 결과 revision/요청 지문만 남긴다. 동일 opId와 동일 receipt는 중복 쓰기 없이 ACK. 서로 다른 receipt/서버 값/리비전은 conflict.
- Rules: 새 receipt와 문서의 lastSyncOpId를 getAfter로 상호 검증, owner와 ready 계정 확인, 신규 receipt만 허용, 수정/삭제/타계정 접근 차단. 이전 최초 이행 문서는 기존 경로를 유지하되 동기화 이력이 생긴 문서의 receipt 우회 및 tombstone 되살리기를 차단한다. 삭제된 부모에 기록 추가/수정 금지. 사진 변경은 아직 차단.
- 실제 Firebase 개발 Rules 배포 또는 Google Play 변경 없음. Emulator JS 테스트로 atomic receipt, 타계정/미준비 계정, 응답 유실 replay, 리비전 충돌, tombstone 확인. Android CI로 dev adapter 컴파일 검증.
- 전송 중 이미 서버에서 승인된 transaction은 계정 전환으로 취소된다고 보장하지 않는다. 서버는 인증 UID 경계를 적용하고, 계정 전환/응답 유실 시 같은 opId를 보존해 재확인한다.
- WorkManager 활성화 전 필요한 작업: 계정별 검증된 기준선·동의/로그아웃/복원/계정 스위치 guard, 연결 조건·유일 작업 이름·중복 실행 방지·재시도 정책과 단위/계측 테스트. 다음 B3에서 준비하며 현재 자동 네트워크 요청 없음. 실서버 QA 및 선행 PR 검증 전 main 병합 금지.
