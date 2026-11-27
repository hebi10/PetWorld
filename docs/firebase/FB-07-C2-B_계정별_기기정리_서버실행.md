# FB-07-C2-B — 완료 확인 후 계정별 기기 정리·서버 실행 준비

선행 #72 0bd55c3f5a5e0f5e95cc38b4fdf45124fbcbeb8e. 실제 사용자 데이터/서버 삭제·배포 없음.

- AccountDeletionLocalCleanup: journal의 대상 계정 및 job ID, 명시적 기기 정리 확인, 서버 COMPLETE, Guest 전환, 백업 복구 저널 부재, 별도 coordinator의 quiescence, 닫힌 Room scope 모두 확인. PetWorldMutationGate에서 이미 닫힌 대상 DB를 예약하고 새 DB 재개방을 프로세스에서 차단. exact DB + SQLite sidecar, 해당 계정 사진 디렉터리, 계정 선택 DataStore 키, 최초 보관 checkpoint 키만 정리하고 부재 검증. symlink 및 canonical 경로 이탈 거부, 중간 실패/재시도 및 journal 유지. 비회원·다른 계정·공통 테마·외부 백업 보존. 대상 SDK 공유 캐시 및 사진 임시 파일은 별도 정책 검증. 기기 정리 전체 완료/포렌식 삭제를 의미하지 않음. 실제 앱에서 quiescence를 증명하는 coordinator/선택 UI 아직 미연결.
- 기존 계정 격리 Android 기기 검사에 실제 Android Keystore AES-GCM 저장/재읽기, 상태 역행·타 계정 ciphertext·변조 거부, synthetic A/B 계정 DB·사진·설정·checkpoint 정리·재시도·symlink 안전 테스트를 추가. 기존 테스트와 함께 API29/35/36에서 실행. 테스트만 합성 자료 삭제.
- server runtime: 검증된 dev-only Admin 서비스, HTTP request/status 경계와 내부 scheduled batch를 묶는 등록 전용 계약. Admin 소유 durable job 목록에서 정렬된 제한 페이지를 검증하고 기존 lease/CAS를 통해 한 번씩 진행. 응답 유실과 독립적으로 재조회할 수 있는 cursor 계약, 처리 실패/보존 검증 대기 상태 구분, 외부 advance 경로 없음. 실제 listener/queue/scheduler 등록, App Check/abuse admission, Firestore index 및 지속 cursor, 단일 작업 제한시간/보존 감사/실서버 검증은 후속. 호출 전 trusted readiness 요구. batch budget은 다음 작업 시작 제한이며 진행 중 SDK 호출을 취소하지 않음.
- 계정 삭제 요청 실제 활성화 금지. 실제 Firebase SDK 공유 캐시 격리/보존 정책과 안전한 quiescence, 인증/재조회 화면·외부 웹 요청, 최종 QA가 남아 있음. CI 성공 전 검증 완료로 표시하지 않음. main/Play/Firebase/웹 배포 없음.
