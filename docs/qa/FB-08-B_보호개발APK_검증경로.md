# FB-08-B 보호된 개발 APK 검증 경로

PR #86의 검토한 소스에서 `com.haebi.petworld.dev` APK를 만들고 기존 개발 서명으로 서명한다. 공개 저장소의 Actions에는 암호화된 Firebase 클라이언트 설정·중간 APK·서명 APK와 검증용 메타데이터만 보관한다. Firebase 운영 설정, 사용자 데이터 및 Google Play는 이 워크플로에서 변경하지 않는다.

## QA 실행 전 환경 설정

- `petworld-dev-signing`은 기존 `main` 전용 개발 서명 환경이다. 기존 개발 설정·서명 Secrets와 새 `PETWORLD_FB08_TRANSIT_KEY`, `PETWORLD_FB08_HANDOFF_KEY`를 이 환경에 둔다.
- `petworld-dev-qa-artifact-transfer`는 `main`에만 허용하고 `PETWORLD_FB08_TRANSIT_KEY`만 저장한다. 후보 PR 소스가 실행되는 빌드 작업은 이 환경을 사용한다. 개발·Play 서명키, 전체 Firebase 설정, 결과 복호화 키는 이 환경에 두지 않는다.
- QA 실행마다 독립적인 256비트 난수 두 개를 소문자 16진수 64자로 생성한다. 설정과 중간 APK 전송에는 양쪽 환경의 동일한 transit 키를 사용한다. 서명 APK에는 signing 환경의 별도 handoff 키를 사용한다. 키는 커밋, 워크플로 입력값, 로그, QA 보고서에 넣지 않는다. 환경 및 Secrets 설정은 저장소 운영자가 승인한 후에만 진행한다.

## 실행과 기기 검증

1. 이 워크플로를 `main`에 반영한 후 해당 `main`의 Android CI와 OPS-08을 확인한다. 기기 QA에는 공통 기준 커밋이 일치하고, `main` 쪽의 추가 변경이 이 QA 파일들과 정확히 검토한 #88 배포 검사 파일로만 구성된 PR #86의 소스를 사용한다. 승인 목록 밖의 변경 또는 소스 변경은 다시 검토한다.
2. `FB-08 QA-only signed development APK`를 `main`에서 실행한다. `pr_sha`에는 검증한 PR #86 전체 HEAD SHA를, `confirmation`에는 `FB08-DEVICE-APK-ONLY`를 입력한다.
3. QA 담당자만 `petworld-fb08-encrypted-device-qa-*`를 내려받아 승인된 기기에서 handoff 키로 APK를 복호화한다. 로컬의 안전한 경로에서 handoff 키를 `QA_ARTIFACT_KEY` 환경 변수로만 제공한다. 설치 전 `verification.json`의 소스 SHA·APK SHA-256·서명 인증서 SHA-256을 대조한다.
4. 개발 테스터 계정으로 Google 로그인, Firestore·Storage·계정 전환 및 복구를 검증한다. 계정 삭제의 기존 차단과 데이터 보존 조건을 유지한다. 실제 검증 결과를 기록한 뒤 QA용 키를 회수한다. APK 보관이 필요하면 접근이 제한된 위치를 사용한다.

워크플로 성공은 APK 생성·서명·암호화의 증빙이다. 기기 QA, PR #86 병합, 동일 `main`의 최종 Android QA와 서명 AAB 준비는 별도로 완료한다.

```sh
node scripts/fb08/qa_device_artifact_cipher.mjs decrypt signed-apk \
  PetWorld-fb08-device-qa.apk.encrypted PetWorld-fb08-device-qa.apk
```
