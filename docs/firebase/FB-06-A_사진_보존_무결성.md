# FB-06-A — 계정별 사진 보존·무결성 저장 기반

- 기획: FB-06 Cloud Storage 사진 보관·동기화 (Google Drive 1-yiPiNlBGUE_iPLsREaKB9MWCaFUhWjIEcvn9dgjk3w).
- 선행 Draft #62 HEAD ff250136b55fdcfda246d1ebfcf49b88800a9e23. 기존 Guest 사진 삭제 동작 및 백업 규격을 유지한다.
- Room v5→v6는 계정별 sync_photos만 추가한다. UUID/경로, SHA-256, 실제 바이트 크기, 해상도, 상태·퇴역 여부를 기록한다. 기존 v1~v5 데이터 보존 테스트 추가.
- 로그인 계정의 사진 변경·삭제와 메타데이터 및 Outbox 변경을 동일 트랜잭션에 기록. 새 사진 저장 실패 시 기존 경로 보존. 이전 사진은 동기화·충돌·참조 정리 검증 전 물리 삭제하지 않는다. Guest 동작 변경 없음.
- 파일당 5MiB, 최대 1200px, JPEG 타입/디코딩, UUID 파일명, SHA-256 검증. 다운로드 바이트 설치 API는 계정 저장소에만 허용하고 동일 이름 다른 바이트를 덮어쓰지 않는다. 임시 파일/fsync/rename. 아직 기존 legacy/손상/누락 파일은 REVIEW_REQUIRED.
- 실제 Storage 전송·다운로드 adapter, 원격 사진 매핑 및 Firestore 참조 승인, 사진이 있는 수신 데이터 재처리, 비용/할당량/보존 종료 정책은 FB-06-B/C에서 개발. 기존 SyncCommands의 사진 차단 유지. 퇴역 데이터와 고아 파일은 현재 자동 정리하지 않는다.
- 테스트: JPEG 설치 및 hash/name 충돌, 계정별 분리, 삭제 시 보존, 누락·legacy review, Guest 무기록, v5 migration. 실제 Storage/Firebase Rules 게시/Play 변경 없음.
