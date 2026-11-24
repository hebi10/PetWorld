# FB-04-REAL-QA-FIX — 암호화 백업 검증 및 실서버 QA 준비

- 기준일: 2026-11-14. PR #37 Draft 범위에서만 진행한다. Google Play 배포, main 병합, Firebase 운영 Rules 변경은 하지 않는다.
- 먼저 Guest 설정 > 데이터 관리에서 .petworld 암호화 백업을 생성한다. 개발용 Google 계정 화면에서 최초 이전 미리보기 → 백업 파일 선택 → 암호 입력 → 검증 완료 후에만 원격 복사 승인 버튼이 활성화된다.
- 검증은 읽기 전용으로 AES-GCM 전체 태그 복호 인증, ZIP manifest 항목별 SHA-256, Room 프로필/기록 UUID 및 값, 선택 반려동물 ID와 사진 바이트 SHA가 현재 Guest 미리보기와 일치하는지 비교한다. 검사 중 Room, 사진, DataStore는 수정하지 않는다.
- 승인 증거는 미리보기 sourceFingerprint와 accountFingerprint에 묶이며, 소스·계정·클라우드 미리보기가 변경되면 기존 업로드 방지 가드가 거부한다.
- 비밀번호는 검증 시에만 사용하고 완료 후 메모리 내 CharArray를 초기화한다. 로그·GitHub 아티팩트·Drive에 비밀번호, UID, 토큰을 저장하지 않는다. 본 검증은 현재 선택한 백업 파일의 유효성 확인으로, 파일의 장기 보존을 보장하지는 않는다.

## 기기·실서버 QA 수행 조건

1. PR #37의 최신 정확한 HEAD SHA에서 보호된 개발키로 서명한 firebaseDev APK와 실 Google OAuth 설정이 준비돼 있어야 한다. 기존 보호 규칙을 완화하거나 main에 임시 병합하지 않는다.
2. 격리된 petworld-dev 테스트 계정, Google Play Services 지원 Android 기기, 개발 프로젝트의 Firestore·Storage Rules/버킷 readback, 테스트 데이터 보존·삭제 정책을 미리 승인받는다.
3. 실사용자의 비회원 데이터 대신 별도 Guest 테스트 프로필과 JPG 파일을 이용한다. 0/1/1000건·NULL/측정값 0·시간대·UUID·사진 SHA-256 매트릭스를 수행하고 업로드 전후 카운트/해시를 기록한다.
4. 취소 시 원격 쓰기 없음, 두 계정 간 미리보기 재사용 차단, 동일 ID 다른 내용 충돌 거부, 업로드 도중 네트워크/프로세스 종료 뒤 재시도, 원격 별도 재조회로 migrationState=ready 시점 정확성을 확인한다.
5. 테스트용 사용자가 UI에서 명시적으로 승인했을 때만 원격 기록을 전송한다. 자동 실서버 쓰기·삭제·테스트 계정 정리를 실행하지 않는다.

## 오프라인 회귀

- :app:testDebugUnitTest, :app:lintDebug, :app:assembleDebug, :app:assembleFirebaseDev(합성 설정).
- PetWorldBackupRestorerTest: 원본 DB 보존·복호 인증·파일 무결성·암호 오류/변조 거부.
- FirstImportBackupVerifierTest: 현재 Guest 지문·계정 승인 결속, NULL/0·사진 해시·선택 ID 불일치 차단.
- Emulator Firebase Rules, Guest/A/B 계정별 Room API29/35/36, 개발 계정 UI smoke 결과는 최신 SHA에서 별도 판정한다.

판정: 자동 QA 성공은 실제 OAuth·Firebase 서버 E2E 성공이 아니다. 실기기 QA 증빙이 없으면 FB-04-REAL-QA는 NO-GO.
