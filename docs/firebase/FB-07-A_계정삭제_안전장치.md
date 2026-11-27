# FB-07-A — 계정 삭제 잠금 및 서버 처리 계약

- 선행 PR #65 HEAD 25a4b166defef1884f935fc0a3dcf96d8c6d8fcf; FB-07 Google Drive 기획 기준. 아직 실서버 QA 전이므로 별도 Draft.
- Admin 전용 accountDeletionLocks/{uid} 존재 시 Firestore 및 Storage의 기존 인증 UID도 접근·재업로드 불가. 클라이언트는 잠금 읽기/생성/제거 불가. Storage Rules의 Firestore default DB 접근은 실제 프로젝트 IAM 연결과 비용 확인 필요.
- 서버 전용 JS 상태 머신: 검증된 Google ID 토큰의 auth_time 5분 이내, 명시적 요청, UID 기반 idempotent job. 신뢰된 adapter의 원자 lock/lease/CAS가 전제. Auth 비활성·토큰 철회 → Firestore 전체 트리 → Storage → Auth 삭제 → 실제 잔존 데이터 및 정책 확인 → 완료. 오류나 미확인 증빙은 완료 처리하지 않으며 lock 유지.
- 현재는 **배포되지 않은 서버 코어와 adapter 계약**이다. Firebase Admin SDK adapter/실제 HTTP 또는 callable endpoint, 인증 검증, 삭제 작업 큐, 기기 캐시 삭제, 외부 요청 웹·운영 정책은 후속. 테스트는 fake port와 Emulator Rules만 사용한다. 어떠한 실제 계정도 삭제하지 않는다.
- 운영 개인정보처리방침 대신 미게시 개정 초안을 추가했다. 현재 배포용 docs/privacy-policy.html은 변경하지 않음. 실제 설정·법적 검토·보존 정책·웹 요청 기능 확인 이후 공개.
- FB-07-A 검증: stale token access, UID 격리, 타 계정 접근, 잠금 위조/삭제, 삭제 후 재생성 차단, 재인증/요청·실패 재시도·실제 증빙 필수·완료 중복 방지. Firebase/Play 배포 및 main 병합 없음.
