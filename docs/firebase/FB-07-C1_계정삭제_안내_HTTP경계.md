# FB-07-C1 — 계정 삭제 화면·HTTP 경계·외부 안내 초안

선행 #68 Admin 처리 및 #69 디자인 후보 890fdf118aabafdbacbeea0a880fd5e6fb0ecdab. #69 설정 QA 재검증과 병행하는 별도 Draft. #70 검증 브랜치의 테스트 전용 파일은 포함하지 않는다.

- FirebaseDev의 로그인 계정에만 실제 계정 삭제 안내 진입 버튼을 연결. 삭제 범위, Google 계정 자체/비회원/타 계정/외부 백업 제외, 미전송 자료와 현재 계정 캐시 처리 제한, 재인증부터 검증까지 절차를 명시. 안내 확인은 실제 동의 제출이 아니며 화면을 닫으면 초기화한다. 네트워크·재인증·계정/기기 자료 삭제를 호출하지 않는다.
- 공통 AccountDeletionReceipt/Phase 및 readiness 계약 추가. COMPLETE만 서버 완료, 다른 job/역행/미정의 상태는 거부. 로그인·동의·최근 인증·검증된 endpoint·안전한 상태 티켓·동기화 차단·기기 정리 증빙을 모두 요구하는 클라이언트 조건을 테스트. 아직 이 모델과 실제 전송은 연결하지 않는다.
- 기존 Admin service 앞단의 framework-neutral HTTP handler: request/status POST만, 엄격한 JSON body/크기/인가 형식, 신뢰된 ingress·App Check/속도제한 admission adapter 필수, 기본 503, no-store, 최소 jobId/phase 응답, 처리 결과 불확실 시 RESULT_UNKNOWN, 사용자 UID·내부 오류·티켓 노출 금지. advanceTrusted 외부 공개 금지. 실제 HTTPS listener, App Check verifier, durable rate limiter, 작업 큐는 아직 배포/등록하지 않음.
- firebase/web/account-deletion-preview.html은 미게시 안내 초안이며 공개 docs 및 Hosting 설정은 변경하지 않는다. 실제 Google 인증/삭제 요청 화면, 상태 티켓 저장/분실·응답 유실 복구, 재인증, 계정별 업로드 차단과 완료 후 안전한 캐시 삭제는 C2 이후.
- 단위 테스트: 상태·계정별 요청 조건·HTTP 거부/최소 응답/불확실성/다른 job. FirebaseDev 비회원 smoke에서 삭제 버튼 미노출 확인. 실제 로그인 계정·삭제 요청·서버 작업·실기기 QA는 미완료.
- Google Drive FB-07 정책 문서 기준. 사용자에게 제출할 실제 삭제 URL 및 개인정보처리방침/Data safety는 운영 검증 이후 반영. main 병합, 실제 계정 삭제, Firebase/웹/Google Play 배포 없음.
