# FB-04-REAL-QA-DEVICE — PR SHA 개발 APK 빌드·보호된 서명 분리

범위: PetWorld PR #37 검증 소스의 Google Play 배포와 무관한 설치용 firebaseDev QA APK. main 병합, Google Play 업로드, Firebase 서버 Rules·사용자 기록 쓰기·삭제는 이 변경에 포함하지 않는다.

## 진행 조건

- 이 문서와 .github/workflows/fb04-device-apk.yml은 별도의 Draft PR로만 준비한다. GitHub workflow_dispatch는 파일이 기본 브랜치에 병합되기 전에는 실행할 수 없다.
- 실제 수동 빌드 활성화 전 최신 main과 신규 워크플로의 보안 리뷰·전체 CI·사용자 병합 승인을 필요로 한다.
- 기존 petworld-dev-signing의 main 전용 보호 정책과 승인 절차를 유지한다.
- 이 QA APK는 com.haebi.petworld.dev 개발 앱에만 적용된다. 기존 Play 버전 com.haebi.petworld, 업로드 인증서, AAB 빌드/릴리스 정책에는 손대지 않는다.
- 수동 실행에는 정확한 PR #37 40자리 HEAD SHA와 FB04-DEVICE-APK-ONLY 문자열을 입력한다. 지정된 HEAD가 이동하거나 대상 CI가 미통과면 fail closed.

## 격리된 단계

1. Preflight: main에서 읽기 전용 GitHub API로 PR #37의 상태·출처·SHA와 필수 CI 8종 성공을 대조한다. SHA 고정. 권한은 contents:read.
2. Public config: 기존 보호 환경에서 Firebase 개발용 google-services.json을 읽되 Android SDK가 원래 APK에 포함하는 공개 클라이언트 필드만 추출한다. 비밀 서비스계정·개인 토큰·키스토어는 전달하지 않는다. 1일 보존 단기 아티팩트. Firebase 프로젝트/패키지가 어긋나면 차단.
3. Unprivileged build → Protected sign: 보호 키나 토큰이 전혀 없는 빌드 잡에서 PR 소스의 Gradle로 정확한 SHA의 개발 APK를 빌드한다. 패키지·Firebase 프로젝트·web OAuth 리소스·APK SHA-256을 확인한다. 다른 보호된 main 전용 서명 잡이 이 APK와 실행/PR 출처를 재검증한 후 기존 고정 개발 키로 apksigner 재서명한다. 이 서명 잡에서는 PR 코드를 체크아웃하거나 Gradle/PR 스크립트를 실행하지 않는다.

서명 증빙은 APK SHA-256, PR SHA, 인증서 SHA-256, 패키지와 프로젝트 ID만 기록한다. 서명키·암호·Firebase 설정 원본·원문 계정·개인 데이터는 출력하지 않는다. 결과 아티팩트는 7일 보존한다.

## 주의 사항

- 공개 Android Firebase client config에는 앱 내부에 원래 포함되는 Firebase API key와 OAuth 클라이언트 식별자가 들어갈 수 있다. 이것은 서비스계정 자격증명은 아니지만, Firestore/Storage Rules·API key 제한·과금 경보가 검증되기 전에는 워크플로를 활성화하지 않는다. Repo Actions 아티팩트 접근 권한도 점검한다.
- 의도적으로 개발용 서명키만 사용한다. Google Play 출시용 업로드 키나 앱 서명키는 사용할 수 없다.
- APK가 서명됐다는 사실만으로 실기기 로그인·서버 이전·계정 분리·업로드 재시도가 성공한 것은 아니다.
- 기존 설치 앱이 다른 인증서면 사용자 데이터를 지우지 말고 별도 테스트 기기/프로필에서 테스트한다. 기존 Guest 자료 자동 삭제나 자동 이전은 없다.
- Firebase Rules는 아직 삭제를 제한하므로 테스트 데이터 보존 및 삭제 절차 승인 전에는 서버에 합성 기록을 쓰지 않는다.

## 합격 기준

- PR #37의 최신 검증 HEAD, 필수 PR CI 성공, 자동 Play 차단 유지.
- 다른 커밋·다른 프로젝트·다른 패키지·잘못된 입력·환경 보호 미승인 시 APK 빌드/서명 불가.
- 동일 APK 바이트의 SHA를 전달받아 보호된 개발 인증서로만 서명; 인증서 SHA-256 검증.
- APK/검증 JSON만 보관, 그 외 Secrets/토큰·사용자 기록 노출 없음.
- 실기기 QA는 별도 수행하며 Google 로그인→암호화 백업 검증→최초 이전/취소→충돌→중단 재시도→사진 SHA-256을 독립 테스트 계정으로 확인한다.

현재 상태: 워크플로 설계 및 Draft PR 검증 단계. 사용자 승인 없는 main 병합과 APK 서명 실행·Google Play 배포는 금지.
