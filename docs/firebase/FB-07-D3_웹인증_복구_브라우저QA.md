# FB-07-D3 — 실제 브라우저 웹 인증·복구 화면 통합 QA

선행 #84 HEAD bb1369949e53fcccaca48442833b12d6bd097403의 Firebase Rules/웹·서버 80/80 및 로컬 삭제 E2E 성공. Android 관련 검사는 별도 상태를 확인한다.

- 기본 account-deletion-preview.html은 계속 비활성 상태로 제공하며 script를 자동 연결하지 않는다. mountReviewedWebDeletionPage는 검토된 host headers, secure context, 동일 HTTPS origin, 기존 readiness, 실제 appCheck 및 GIS 객체가 공급될 때만 한 번 mount한다. query parameter, localStorage, 사용자 입력으로 readiness를 활성화하지 않는다. 실제 Web SDK/App Check/GIS 설치 및 도메인 설정, 호스팅/서버 기동은 수행하지 않았다.
- 암호화 복구 파일 다운로드만으로는 삭제 요청을 할 수 없다. 사용자가 저장한 파일과 비밀번호를 다시 읽어 확인한 후 별도 보관 동의 및 삭제 승인 체크를 해야 제출할 수 있다. 잘못된 비밀번호·파일·계정은 차단한다. 복구만 필요한 비로그인 사용자는 파일 확인 후 상태 조회만 가능하다. 화면 이탈/계정 전환 도중 파일 읽기 결과를 반영하지 않는다. 인증 dialog에 접근 가능한 제목·설명과 닫기 이벤트를 추가했다.
- Chromium QA는 기존 페이지 및 bootstrap·controller·GIS prompt·transport의 실제 JS를 실행하고 브라우저 WebCrypto 암호화, 다운로드, 파일 업로드, 동의, 상태 표시를 검증한다. 모든 요청을 Playwright browser context에서 차단/가로채며 예약된 .invalid origin과 지정된 route에 한해서만 실제 Node HTTP handler에 전달한다. 공식 Google Auth Library로 합성 RSA 서명을 검증하고 기존 Web identity/nonce/limiter/lock/status 코드를 사용한다. 서버 Auth·Firestore 포트 및 App Check/GIS는 합성 fixture. 실제 Google 로그인/JWKS, 운영 Firebase, 브라우저-서버 TLS 및 실제 배포 CSP 검증은 아니다.
- 테스트 3개: 비활성 기본 페이지 및 390px 모바일 overflow; 인증 취소·미등록 계정 거절·복구 파일 다운로드/재검증·잘못된 비밀번호·최근 auth_time 누락/만료·다른 계정 요청 차단·응답 유실 후 기존 lock 조회·인증 삭제 이후 복구·단계별 완료 표시; pagehide 중 인증 세션 무효화. local/sessionStorage 비어 있는지 확인. 테스트 전용 Playwright 1.65.1을 CI에서만 설치한다. 실제 사용자 정보 없이 화면 PNG 3개만 업로드한다. 최신 SHA 검사 성공 전 실제 브라우저 검증 완료라고 보고하지 않는다.
- 남은 실제 검증: Web App 및 OAuth client 등록, GIS auth_time/security bundle 실제 지원 및 재인증 UX, App Check, 동일 origin 웹 호스트/CSP/TLS, Firestore TTL, 실제 IAM/Storage 보존/worker 설정, 기존 설치 공유 캐시 및 PIN/Keystore/Room/image/listener 종료/복구 슬롯 해제. 현재 기본 화면과 서버의 실제 계정 삭제 경로 활성화는 차단 상태. 다음은 신규 CI 및 실제 환경 검증 조건 정리·FB-08 통합 QA. 실제 사용자 계정 삭제, main 병합, 서버/웹/Play 배포 없음.

참조: https://playwright.dev/docs/network ; https://playwright.dev/docs/api/class-download .
