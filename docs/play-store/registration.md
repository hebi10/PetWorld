# PetWorld Play 등록 및 Actions

대상은 `hebi10/PetWorld`의 `com.haebi.petworld`입니다. 현재 main의 기능만 배포하며 Draft PR #5는 포함하지 않습니다.

| 항목 | 기준 |
| --- | --- |
| 앱 이름 | PetWorld |
| 최초 버전 | 0.1.0 · versionCode 1 |
| Android | 8.0 이상 · targetSdk 36 |
| 개인정보처리방침 | https://hebi10.github.io/PetWorld/privacy-policy.html · Pages 배포 후 실제 응답 확인 필요 |
| 기본 트랙 | 내부 테스트 internal |
| 비공개 테스트 | 사용자 요청 시 이미 완료된 internal 코드만 alpha 승격 |
| 프로덕션 | 지원하지 않음 |

## 자동 배포

현재 main 커밋의 `Android CI` 전체가 성공하면 `Play internal release`가 exact SHA를 확인하고 동일 소스를 빌드합니다. 앱 전용 서명 인증서, AAB·APK의 실제 패키지·버전, 포함된 모든 ELF 및 APK ZIP의 16KB 정렬 검사를 통과한 경우에만 업로드합니다. 네이티브 라이브러리가 실제로 없는 AAB/APK 쌍은 그 사실을 기록하며 잘못된 ELF나 한쪽에만 있는 라이브러리를 무시하지 않습니다.

Play의 모든 등록 AAB·APK·트랙보다 큰 versionCode를 자동 할당하고 업로드 직전에 충돌을 다시 확인합니다. Google가 반환한 업로드 해시가 실제 AAB와 일치해야 커밋합니다. 커밋 후 새 편집에서 버전과 트랙을 다시 조회합니다. API 반영 성공은 Console 심사·게시 및 실제 설치 성공과 구분합니다.

## 수동 실행

Actions → `Play internal release` → `Run workflow` → `main`에서 모드를 선택합니다.

| 모드 | 동작 |
| --- | --- |
| check | 소스·회귀 검증 및 WIF API 조회. 업로드·트랙 변경·커밋 없음 |
| build | 고정 업로드 키로 AAB·APK를 빌드·검증하고 Actions 산출물 보관. Play 변경 없음 |
| initialize | 빈 AAB·APK·모든 트랙 inventory에만 코드 1을 internal draft로 최초 제출·재조회 |
| deploy | 실제 등록 바이너리를 인증 조회한 뒤 새 버전을 internal에 제출·커밋·재조회 |

최초 등록은 명시적 initialize 실행 또는 검증된 main 커밋 제목의 독립 토큰 `[initialize-play]`로만 진행합니다. PR·포크·태그·다른 브랜치·커밋 본문 예시는 시작하지 않습니다. 이미 등록된 앱을 초기화하지 않습니다.

최초 초안 커밋에서 Google의 정확히 알려진 `changesNotSentForReview` 금지 오류가 확인된 경우에만 같은 편집에서 해당 파라미터를 제외해 한 번 재시도합니다. `ERROR_IF_IN_REVIEW`는 유지하며 업로드를 반복하지 않습니다. 다른 오류나 불확실한 커밋 결과는 자동 재시도하지 않고 Console 확인 후 판단합니다. 해당 파라미터 생략은 Google의 자동 심사 동작에 따라 대기 중인 변경을 심사로 보낼 수 있습니다.

`Play closed promotion`은 수동 check/promote만 제공합니다. promote는 이미 완료된 internal의 정확한 versionCode를 alpha에 승격하며 새 AAB 업로드·빌드를 하지 않습니다.

## 앱별 환경

`google-play` 환경은 main 브랜치만 허용해야 합니다. 전용 WIF는 저장소 ID·소유자 ID·저장소 이름·main ref·google-play 환경을 모두 제한하며 서비스 계정은 이 앱의 테스트 출시 권한만 사용합니다. 다른 앱의 키나 계정을 사용하지 않습니다.

준비할 공개 구성값은 프로젝트 `hebi-app-release-80847404`(번호 `973619009476`), 전용 계정 `play-petworld@hebi-app-release-80847404.iam.gserviceaccount.com`, WIF 풀 `petworld-github`·공급자 `github-main`입니다. 신뢰 조건은 저장소 ID `1450869477`, 소유자 ID `80847404`, 저장소 이름 `hebi10/PetWorld`, `refs/heads/main`, `google-play` 환경을 모두 제한합니다. 이 값들은 구성 계획이며 실제 생성·권한 등록·인증 성공은 별도 검증합니다.

| 종류 | 이름 |
| --- | --- |
| Secrets | ANDROID_RELEASE_KEYSTORE_BASE64, ANDROID_RELEASE_STORE_PASSWORD, ANDROID_RELEASE_KEY_ALIAS, ANDROID_RELEASE_KEY_PASSWORD, ANDROID_RELEASE_CERT_SHA256 |
| Variables | PLAY_WORKLOAD_IDENTITY_PROVIDER, PLAY_SERVICE_ACCOUNT |
| 실행 중 검증 | PLAY_CONSOLE_INITIALIZED ← 실제 등록 바이너리와 편집 정리 확인 후 step 출력만 전달 |

키가 없거나 인증서가 다르면 중단합니다. workflow 실행 중 새 키를 만들지 않으며 비밀값을 코드·로그·일반 문서에 기록하지 않습니다. 업로드 키의 복구 백업은 소유자 전용 비공개 위치에서 관리합니다.

## 등록 및 정책 확인

현재 main은 인터넷 권한·광고·로그인·결제·분석 SDK 없이 프로필·선택한 사진·기록·설정을 기기에 보관합니다. 현재 앱에서 사용자 데이터가 서버로 전송되는 기능을 주장하지 않습니다. 향후 기능이나 Draft PR의 암호화 백업을 현재 배포 기능으로 설명하지 않습니다.

앱 생성, Play App Signing 등록, 공개 방침 URL, 연락처, 콘텐츠 등급, 대상 연령, Data Safety, 판매자 상태와 스토어 이미지·문구는 Console의 실제 질문과 현재 기능을 대조해 완료합니다. 내부 전용 Data Safety 예외를 비공개·프로덕션까지 확대 해석하지 않습니다. 무료라는 사실만으로 판매자 여부를 확정하지 않습니다.

## 검증 상태

이 문서는 구현 기준입니다. 계정·환경 설정, 실제 Google Play 업로드·게시·기기 설치가 완료됐다는 증거로 사용하지 않습니다. 실제 실행 결과는 PR과 Actions의 비밀값 없는 보고서에 기록합니다.
