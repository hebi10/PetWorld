# FB-05-A — 계정별 로컬 동기화 Outbox 기반

- 선행: Draft PR #55, HEAD 21aefd6d4ce706c4c6aa49e76899d67c67af8c07. FB-04 실제 서버 QA 전 별도 스택에서 오프라인 구현만 수행.
- Room v2→v3는 sync_outbox 테이블과 인덱스만 추가한다. pets/care_entries 스키마·UUID·사진 경로·기존 논리 백업 schemaVersion=2 유지. v1→v2→v3 및 v2→v3 검증.
- 로그인 계정의 프로필/기록 생성·수정·보관·삭제와 대기열 추가를 동일 트랜잭션에서 실행한다. Guest는 기록하지 않는다. 프로필 영구 삭제는 연관 기록 snapshot을 먼저 남긴다.
- 대기열은 단조 증가 sequence, UUID opId, 해시 계정 key, 대상, 원본 전후 snapshot(JSON, NULL/0 유지), operation, createdAt, status, attempt, nullable expectedRevision을 저장한다.
- 아직 원격 기준 리비전이 확정되지 않았으므로 모든 항목 WAITING_BASELINE. 서버 revision을 임의 0으로 가정하거나 자동 전송하지 않는다. 사진 파일명만 기록하며 바이트 보존/업로드는 FB-06 별도 설계 대상이다.
- 현재는 로컬 저널·원자적 기록·보존 단계다. WorkManager, 서버 idempotency, revision 조회 및 충돌 보존, tombstone 전송/내려받기, 상태 UI, 계정별 백업 정책은 후속 FB-05-B~D에서 구현한다.
- 활성화 전 실제 FB-04 개발 서버 검증, 승인된 baseline, 사진 보존 및 기존 계정 DB와 대기열의 계정 귀속 확인 필요. Firebase Rules, 실제 서버 데이터, Google Play, #55 변경 없음.
- 검증: Guest 무기록, 계정 A/B 조회 분리, CREATE/UPDATE/DELETE 순서와 이전값 보존, NULL vs 0, 실패 시 대기열 없음, 트랜잭션 rollback, 프로필 cascade, v1/v2 데이터 보존. 최신 HEAD CI 통과 전 병합 금지.
