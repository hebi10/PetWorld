# FB-05-B3 — WorkManager 자동 전송 준비 및 계정 세션 경계

- 선행: PR #58 수정 HEAD 2b0c23410835a713d10d924253bf1e29ed452e7b. WorkManager 2.10.1은 FirebaseDev variant만 사용한다. 일반 debug/release에는 스케줄러 의존성을 추가하지 않는다.
- 네트워크 연결 조건, 계정 해시와 세션 세대별 고유 작업 KEEP, 지수 백오프 시작 30초(WorkManager 자체 상한 적용), 최대 20개씩 drain. 충돌/미확정 기준선/차단/권한 상실 시 후속 작업을 넘기지 않는다.
- 검증된 계정·사용자 동의·실서버 QA·기준선 지문으로만 프로세스 내 lease를 발급하는 계약. 실제 앱의 activate/enqueue 연결은 하지 않는다. 프로세스 재시작 후 이전 WorkManager input은 권한 증빙으로 인정되지 않으며, 다음 단계에서 인증 및 기준선 재확인 후 새 lease를 발급할 예정이다.
- 로그아웃/로그인 요청 전 기존 lease를 즉시 무효화하고 해당 작업 취소. 계정 resolver의 변경 감지에도 무효화. worker는 개발 package/project/Google 계정/lease/복원 저널을 확인하고 매 작업 전후 재검증한다. 이미 서버가 수락한 요청을 취소할 수 있다고 가정하지 않으며 receipt 기반 재확인을 유지한다.
- UI가 소유하는 계정별 Room DB를 사용하며 worker가 닫지 않는다. 계정 변경 중 닫힌 DB 예외는 이전 계정의 전송을 멈추고 미확정 receipt 재확인 상태를 유지한다.
- 구현한 것은 비활성 상태의 scheduler 및 worker와 회귀 테스트이다. 실제 동의·baseline 승인 UI, 프로세스 재시작 자동 재승인/변경 이벤트 enqueue 및 원격 수신 충돌 처리는 후속 단계에서 연결한다. 실서버 Rules 게시, 실제 계정 데이터 전송, Play 배포는 없음.
- 테스트: 기본 거부/미완료 증빙/다른 계정/Guest/프로세스 재시작/세대 교체/철회, 배치 처리 제한/대기·충돌·재시도 중단. Android CI에서 개발 variant 컴파일, 실제 WorkManager 기기 실행과 재시작 검증은 활성화 전 추가 필요.
