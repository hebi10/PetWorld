# FB-07-C2-C2-01 — 개발 서버 HTTPS 및 App Check 수신 준비

선행 PR #74 HEAD 348bbcfd37809f84724839387f5fcc516fc5a6ed. 서버·웹·Play 배포 및 실제 사용자 데이터 삭제 없음.

- account-deletion-admission.mjs: Firebase Admin App Check verifyToken 결과의 appId를 서버에서 검토한 Android App ID 허용 목록과 비교한다. 클라이언트의 자체 주장 및 전달 헤더를 신뢰하지 않는다. 검증된 앱별 request/status 할당량을 Firestore accountDeletionAdmission의 결정적 문서에 공유 트랜잭션으로 저장한다. 60초 고정 구간과 승인된 할당량(1~1000)을 사용하며 앱 ID별 최대 두 문서만 사용한다. UID, IP, 티켓, 원본 토큰은 저장하지 않는다. 저장소 장애·손상·시계 역행 시 거부. 이 제한은 앱 단위 총량 보호이며 공격자의 인증 이전 트래픽 제한·개별 사용자별 제한을 대체하지 않는다. 운영 전 승인된 ingress 방어 및 할당량을 별도로 검증해야 한다.
- account-deletion-https.mjs: 실제 Node HTTPS 서버를 생성할 수 있는 등록 전 factory. 직접 TLS(1.2 이상), 검토된 HTTPS DNS origin 및 Host, 정확한 두 POST 경로만 허용. 2048B 고정 길이, 중복 헤더/압축/chunked/Expect/쿼리 차단, 제한시간 및 no-store. Android fixed-length adapter와 일치. 프록시 TLS 종료 구조는 아직 지원하지 않으며 forwarded 헤더는 신뢰하지 않는다. 실제 인증서 또는 endpoint 추가 없음. 실제 listen, 배포, public worker, 환경변수 기반 자동 활성화 없음.
- prepareDeletionRuntime 및 createFirebaseDeletionService의 개발 프로젝트/버킷/Rules/IAM/보존 감사/스케줄러/SDK 캐시 gate를 그대로 유지. 승인된 HTTPS origin/App Check 허용 목록/공유 제한 저장소/인증 이전 ingress 방어에 대한 명시적 증거도 요구. 설정을 임의로 true로 하지 않는다. 내부 worker 등록은 별도 C2-C2-02.
- 테스트: 합성 App Check 성공/거부/타 앱, quota 동시성·새 인스턴스·구간 만료·손상·시계 역행·저장소 장애, TLS 수신 경로/Host/중복 헤더/길이/완료 검증, 최소 응답, prod와 미확인 readiness 거부, Firebase Emulator Rules의 카운터 접근 차단. 실제 Firebase 토큰 발급, 인증서/네트워크 TLS, 실제 SDK 트랜잭션 동시성, 운영 abuse 제어 및 보존·권한 검증은 미완료.
- 다음: C2-C2-02 durable private scheduler/cursor, C2-C2-03 인증 삭제 후 안전한 상태 조회와 quiescence/SDK cache 검증. 전체 연결 검증 전 앱 요청 버튼 비활성 유지. 운영 프로젝트·실사용자 데이터에 접근하지 않는다.
