# FB-05-B2 — 개발용 Firestore 원자적 전송 연결

- 선행 Draft PR #57 HEAD 47725b51495b30787569ebebf9acec6ccec036de. FB-05-B1 재시도 상태 머신 및 #56 계정별 Outbox를 그대로 유지한다.
- FirebaseDev 전용 adapter에서 Google 계정 scope·개발 패키지·프로젝트를 검증하고 실제 온라인 ID 토큰 갱신 후에만 Firestore transaction을 요청한다. 기본 권한은 false이며 앱·스케줄러에서 호출하지 않는다.
- users/{uid}/syncReceipts/{opId}와 대상 문서를 동일 transaction에서 기록한다. 불변 receipt에는 작업/대상/예상 및 결과 revision/요청 지문만 남긴다. 동일 opId와 동일 receipt는 중복 쓰기 없이 ACK. 서로 다른 receipt/서버 값/리비전은 conflict.
- Rules: 새 receipt와 문서의 lastSyncOpId를 getAfter로 상호 검증, owner와 ready 계정 확인, 신규 receipt만 허용, 수정/삭제/타계정 접근 차단. 이전 최초 이행 문서는 기존 경로를 유지하되 동기화 이력이 생긴 문서의 receipt 우회 및 tombstone 되살리기를 차단한다. 삭제된 부모에 기록 추가/수정 금지. 사진 변경은 아직 차단.
- 실제 Firebase 개발 Rules 배포 또는 Google Play 변경 없음. Emulator JS 테스트로 atomic receipt, 타계정/미준비 계정, 응답 유실 replay, 리비전 충돌, tombstone 확인. Android CI로 dev adapter 컴파일 검증.
- 전송 중 이미 서버에서 승인된 transaction은 계정 전환으로 취소된다고 보장하지 않는다. 서버는 인증 UID 경계를 적용하고, 계정 전환/응답 유실 시 같은 opId를 보존해 재확인한다.
- WorkManager 활성화 전 필요한 작업: 계정별 검증된 기준선·동의/로그아웃/복원/계정 스위치 guard, 연결 조건·유일 작업 이름·중복 실행 방지·재시도 정책과 단위/계측 테스트. 다음 B3에서 준비하며 현재 자동 네트워크 요청 없음. 실서버 QA 및 선행 PR 검증 전 main 병합 금지.

## 2026-11-25 CI 실패 보완
- 보안 규칙 파일의 정규식 끝 앵커 인접 문자열 치환으로 기존 match 블록이 중복 삽입된 실제 문법 오류(L108/L200 등)를 복원했다. 규칙 검사를 생략하지 않고 기존 및 신규 Emulator 테스트를 유지한다.
- parent 검증은 트랜잭션 전후 모두 활성 상태여야 한다. 경로의 점 세그먼트도 차단한다.
- API 35 계정 UI 테스트의 제목 탐색 실패를 보완하기 위해 계정 화면에 safeDrawingPadding을 적용하고, 하단 요소는 실제 스크롤 탐색으로 확인한다. 제목 및 게스트 상태 검사는 유지·강화한다. 정확한 화면 원인은 재검증 필요하다.
- 실제 서버 배포 및 전송 활성화 없음.
