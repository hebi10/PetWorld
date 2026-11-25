# FB-07-C2-A — 동일계정 재인증 및 삭제 요청 복구 기반

선행 #71 532d565520dcec1fced408b561edf0ea5e2f13f6. 실제 배포·사용자 삭제 없음. C2를 안전한 클라이언트 상태/인증 기반과 서버 실행·로컬 정리로 분리한다.

- FirebaseDev gateway에 signInWithCredential 대신 현재 FirebaseUser.reauthenticate를 사용하는 동일 UID 재인증 및 fresh ID token 5분 검사 추가. 계정 전환·취소·오류 시 실패, token 로그/표시 없음. 아직 UI와 연결하지 않음.
- UID에서 서버와 동일한 SHA-256 job ID 계산, 32-byte SecureRandom status ticket. 계정별 noBackupFilesDir AtomicFile + Android Keystore AES-256-GCM, account AAD, ticket 및 phase 암호화. 읽기 오류나 잃어버린 키는 신규 요청으로 처리하지 않는다. 서버 완료 후에도 기록 유지. 로컬 자료 삭제는 하지 않음.
- 전송 전에 같은 계정의 티켓 및 job ID 저장 확인. 불확실한 응답은 동일 티켓 재사용, 확인된 상태는 status 조회, 다른 job/상태 역행 거부, COMPLETE만 서버 완료. 실제 endpoint 및 admission/티켓 접근 인증 UI는 미연결. 상태 조회 선택 경로는 별도 검증 필요.
- journal 존재 시 해당 계정의 WorkManager 동기화 권한 및 첫 보관 서비스 checkedUid를 차단하고 계정 UI에서 신규 클라우드 작업 미노출. 이미 시작된 네트워크 요청의 즉시 취소를 보장하지 않으므로 서버 lock Rules가 필수. guest/타 계정에는 동일 journal을 적용하지 않음. 
- 단위 테스트: 응답 유실 및 티켓 재사용, UID 격리, 미준비/깨진 저장소/다른 job 거부, 티켓 형식. Android Keystore 실제 저장·복구와 상태 조회 접근 통제, local DB/photo/preferences 안전 삭제 및 post-delete 재인증/로그아웃, endpoint/queue/App Check/retention, 실제 앱·외부 웹 요청은 C2-B 이후. 아직 UI의 실제 삭제 요청은 비활성 유지.
- main 병합·Play·Firebase·웹 배포 없음.
