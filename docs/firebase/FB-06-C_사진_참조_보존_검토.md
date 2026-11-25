# FB-06-C — 검증된 사진 참조 전환·보존 검토
- 선행 Draft #64 ac8dfdd1b30a73c2fd1330d000791bc99d492f01. FB-06-B 업로드/다운로드 구현을 재사용한다.
- 새 사진 참조는 Storage 업로드·다운로드 검증과 계정별 REMOTE_VERIFIED, 실제 로컬 JPEG 증빙을 다시 확인한 후 FIFO READY/전송. Firestore의 대상 문서·불변 receipt·새 사진 manifest를 동일 transaction에서 기록. 기존 사진을 덮어쓰지 않으며 DELETE tombstone의 기존 참조도 유지한다.
- manifest는 계정/pet/file 경로, SHA-256·크기·해상도·MIME의 불변 **클라이언트 확인 자료**다. Firestore Rules는 Storage 객체 존재/바이트를 독립적으로 검증할 수 없다. 새 sync 사진 참조는 같은 pet manifest와 receipt를 요구하며 준비 완료 계정의 legacy 직접 사진 변경을 차단한다. 기존 pending 최초 이행을 보존한다. Storage 실제 삭제 금지는 유지한다.
- 수신 사진은 다운로드·파일 재검증 및 manifest가 있는 경우 무결성 비교 후에만 기존 revision/parent/outbox 검증을 거쳐 Room에서 반영. 미확정 PHOTO_PENDING은 동일 페이지 재처리 가능. 충돌·tombstone은 기존 검토 정책 유지. 사진 제거/교체 시 이전 로컬 바이트 보존 및 저널 갱신.
- 정리 검토는 계정 DB 전체 활성 사진 참조, 모든 outbox/inbound 변경 이력, 다른 pet의 동일 이름 매핑 및 무결성 상태를 검사한다. 모두 비어도 신뢰된 서버의 모든 기기 참조·보존 정책 확인 전 삭제 금지. 자동 물리 삭제를 구현하지 않는다.
- 테스트: 응답 유실 후 동일 opId, 검증된 manifest 없이 참조 불가, 다른 계정/부모 경로·변경/삭제 차단, 원격 참조 반영과 기존 바이트 보존, manifest hash/해상도 불일치, 보존 후보 분류. 서버 실제 배포·자동 전송 활성화·Play 변경 없음.
- 다음: 실제 dev Firebase QA, 신뢰된 서버 참조 감사/비용·삭제 정책, FB-07 계정 삭제 및 개인 데이터 보존/파기. 충돌에서 사진이 포함된 서버값 수동 선택은 여전히 보류한다.
